package com.asea.policy;

import com.asea.audit.AuditEventType;
import com.asea.audit.AuditService;
import com.asea.common.exceptions.ApprovalRequiredException;
import com.asea.common.exceptions.PolicyViolationException;
import com.asea.common.exceptions.ValidationException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Policy Engine — answers "Is this action allowed by policy?"
 *
 * <p>Separate from the Permission Manager which answers "Is this actor allowed to do it?"
 * Policy rules are tenant-scoped and stored in PostgreSQL.</p>
 *
 * <p>Risk is always evidence-based. The engine considers:
 * <ul>
 *   <li>Explicit policy rules in the database</li>
 *   <li>File path sensitivity heuristics with safe segment/prefix matching</li>
 *   <li>Modification size</li>
 *   <li>Security-relevant operations</li>
 * </ul>
 * </p>
 *
 * <p>All audit metadata is constructed with Jackson ObjectMapper to guarantee
 * valid JSON output even when factor strings contain special characters.</p>
 */
@Service
public class PolicyEngine {

    private static final Logger log = LoggerFactory.getLogger(PolicyEngine.class);

    private final PolicyRepository policyRepository;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public PolicyEngine(PolicyRepository policyRepository,
                        AuditService auditService,
                        ObjectMapper objectMapper) {
        this.policyRepository = policyRepository;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    /**
     * Evaluates whether an action is permitted for the given tenant.
     * Always audits the decision.
     *
     * @param tenantId      the tenant requesting the action
     * @param action        the action being evaluated (e.g. "WRITE_SOURCE", "CREATE_PR")
     * @param taskId        task context for audit logging
     * @param filePaths     file paths affected by the operation (may be empty)
     * @param modifications number of lines/files being modified
     * @return PolicyDecision with risk assessment and contributing factors
     */
    @Transactional(readOnly = true)
    public PolicyDecision evaluate(String tenantId, String action, String taskId,
                                   List<String> filePaths, int modifications) {
        List<String> factors = new ArrayList<>();
        RiskLevel riskLevel = RiskLevel.LOW;
        boolean requiresApproval = false;

        // 1. Check explicit database policy
        Optional<Policy> explicitPolicy = policyRepository.findByTenantIdAndAction(tenantId, action);
        if (explicitPolicy.isPresent()) {
            Policy policy = explicitPolicy.get();
            if (!policy.isAllowed()) {
                factors.add("Explicit policy DENY for action: " + action);
                PolicyDecision decision = PolicyDecision.deny(policy.getRiskLevel(), factors,
                        "Action blocked by policy: " + policy.getName());
                auditDecision(tenantId, taskId, action, decision, AuditEventType.POLICY_DENIED);
                log.warn("Policy DENIED tenantId={} action={} taskId={} policy={}",
                        tenantId, action, taskId, policy.getName());
                return decision;
            }
            riskLevel = policy.getRiskLevel();
            factors.add("Explicit policy found: " + policy.getName() + " risk=" + riskLevel);
            if (policy.isRequiresApproval()) {
                requiresApproval = true;
                factors.add("Policy requires human approval");
            }
            if (policy.getMaxModifications() != null && modifications > policy.getMaxModifications()) {
                factors.add("Modification count " + modifications + " exceeds policy max " + policy.getMaxModifications());
                requiresApproval = true;
                riskLevel = escalateRisk(riskLevel);
            }
        }

        // 2. File path sensitivity analysis with hardened path validation
        if (filePaths != null) {
            for (String rawPath : filePaths) {
                if (rawPath == null || rawPath.isBlank()) {
                    continue;
                }
                String normalizedPath = normalizeAndValidatePath(rawPath);

                if (isCriticalPath(normalizedPath)) {
                    factors.add("Critical path touched: " + normalizedPath);
                    riskLevel = RiskLevel.CRITICAL;
                    requiresApproval = true;
                } else if (riskLevel != RiskLevel.CRITICAL && isHighRiskPath(normalizedPath)) {
                    if (riskLevel.ordinal() < RiskLevel.HIGH.ordinal()) {
                        factors.add("High-risk path touched: " + normalizedPath);
                        riskLevel = RiskLevel.HIGH;
                    }
                }
            }
        }

        // 3. Modification volume
        if (modifications > 500) {
            factors.add("Large modification: " + modifications + " lines");
            requiresApproval = true;
            if (riskLevel.ordinal() < RiskLevel.HIGH.ordinal()) {
                riskLevel = RiskLevel.HIGH;
            }
        } else if (modifications > 100) {
            factors.add("Medium modification: " + modifications + " lines");
            if (riskLevel.ordinal() < RiskLevel.MEDIUM.ordinal()) {
                riskLevel = RiskLevel.MEDIUM;
            }
        }

        PolicyDecision decision = requiresApproval
                ? PolicyDecision.allowWithApproval(riskLevel, factors, "Human approval required for risk level: " + riskLevel)
                : PolicyDecision.allow(riskLevel, factors);

        AuditEventType auditEvent = requiresApproval
                ? AuditEventType.POLICY_APPROVAL_REQUIRED
                : AuditEventType.POLICY_ALLOWED;
        auditDecision(tenantId, taskId, action, decision, auditEvent);

        log.info("Policy ALLOWED tenantId={} action={} taskId={} riskLevel={} requiresApproval={}",
                tenantId, action, taskId, riskLevel, requiresApproval);
        return decision;
    }

    /**
     * Enforces policy — throws {@link PolicyViolationException} if denied,
     * or {@link ApprovalRequiredException} if approval is required.
     */
    public void enforce(String tenantId, String action, String taskId,
                        List<String> filePaths, int modifications) {
        PolicyDecision decision = evaluate(tenantId, action, taskId, filePaths, modifications);
        if (!decision.allowed()) {
            throw new PolicyViolationException("Policy violation for action: " + action
                    + ". Reason: " + decision.reason());
        }
        if (decision.requiresApproval()) {
            throw new ApprovalRequiredException("Human approval required for action: " + action
                    + ". Reason: " + decision.reason());
        }
    }

    /**
     * Normalizes and validates a repository-relative path.
     * Rejects traversal, drive prefixes, absolute paths, and NUL bytes.
     */
    public String normalizeAndValidatePath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            throw new ValidationException("File path must not be null or blank");
        }
        if (rawPath.contains("\0")) {
            throw new ValidationException("Path contains invalid NUL character: " + rawPath);
        }

        // Convert Windows backslashes to standard forward slashes
        String normalized = rawPath.replace('\\', '/').trim();

        // Check for Windows drive letter prefix (e.g. C:, D:)
        if (normalized.matches("^[a-zA-Z]:.*")) {
            throw new ValidationException("Drive prefix paths are not permitted: " + rawPath);
        }

        // Check for absolute path (leading slash)
        if (normalized.startsWith("/")) {
            throw new ValidationException("Absolute paths are not permitted: " + rawPath);
        }

        // Clean leading relative "./"
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }

        // Collapse duplicate slashes
        normalized = normalized.replaceAll("/+", "/");

        // Reject directory traversal attempts ("..")
        String[] segments = normalized.split("/");
        for (String segment : segments) {
            if ("..".equals(segment)) {
                throw new ValidationException("Directory traversal (..) is not permitted: " + rawPath);
            }
        }

        return normalized;
    }

    /**
     * Checks if a normalized path matches any ALWAYS_CRITICAL rule.
     * Uses segment/prefix matching rather than substring checks.
     */
    private boolean isCriticalPath(String path) {
        // .github/workflows/
        if (path.startsWith(".github/workflows/") || path.equals(".github/workflows") || path.contains("/.github/workflows/")) {
            return true;
        }
        // infrastructure/
        if (path.startsWith("infrastructure/") || path.contains("/infrastructure/")) {
            return true;
        }
        // Dockerfile
        if (isFilenameMatch(path, "Dockerfile") || path.startsWith("Dockerfile.") || path.contains("/Dockerfile.")) {
            return true;
        }
        // docker-compose
        if (path.contains("docker-compose")) {
            String filename = getFilename(path);
            if (filename.startsWith("docker-compose")) {
                return true;
            }
        }
        // security/
        if (path.startsWith("security/") || path.contains("/security/")) {
            return true;
        }
        // auth/
        if (path.startsWith("auth/") || path.contains("/auth/")) {
            return true;
        }
        // payment/
        if (path.startsWith("payment/") || path.contains("/payment/")) {
            return true;
        }
        // secrets
        if (isFilenameMatch(path, "secrets") || path.startsWith("secrets/") || path.contains("/secrets/")) {
            return true;
        }
        // .env
        String filename = getFilename(path);
        if (filename.equals(".env") || filename.startsWith(".env.")) {
            return true;
        }

        return false;
    }

    /**
     * Checks if a normalized path matches any HIGH_RISK rule.
     */
    private boolean isHighRiskPath(String path) {
        // src/main/
        if (path.startsWith("src/main/") || path.contains("/src/main/")) {
            return true;
        }
        // src/test/
        if (path.startsWith("src/test/") || path.contains("/src/test/")) {
            return true;
        }
        // pom.xml
        if (isFilenameMatch(path, "pom.xml")) {
            return true;
        }
        // build.gradle / build.gradle.kts
        String filename = getFilename(path);
        if (filename.equals("build.gradle") || filename.equals("build.gradle.kts")) {
            return true;
        }
        // application.properties
        if (filename.equals("application.properties") || (filename.startsWith("application-") && filename.endsWith(".properties"))) {
            return true;
        }
        // application.yml / yaml
        if (filename.equals("application.yml") || filename.equals("application.yaml")
                || (filename.startsWith("application-") && (filename.endsWith(".yml") || filename.endsWith(".yaml")))) {
            return true;
        }

        return false;
    }

    private boolean isFilenameMatch(String path, String targetFilename) {
        String filename = getFilename(path);
        return filename.equalsIgnoreCase(targetFilename);
    }

    private String getFilename(String path) {
        int idx = path.lastIndexOf('/');
        return (idx >= 0) ? path.substring(idx + 1) : path;
    }

    // -------------------------------------------------------------------------

    private RiskLevel escalateRisk(RiskLevel current) {
        return switch (current) {
            case LOW -> RiskLevel.MEDIUM;
            case MEDIUM -> RiskLevel.HIGH;
            case HIGH, CRITICAL -> RiskLevel.CRITICAL;
        };
    }

    /**
     * Builds audit metadata using Jackson ObjectMapper to guarantee valid JSON.
     */
    private void auditDecision(String tenantId, String taskId, String action,
                                PolicyDecision decision, AuditEventType eventType) {
        String metadata;
        try {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("action", action);
            node.put("riskLevel", decision.riskLevel() != null ? decision.riskLevel().name() : null);
            node.put("allowed", decision.allowed());
            node.put("requiresApproval", decision.requiresApproval());
            ArrayNode factorsNode = node.putArray("factors");
            for (String factor : decision.contributingFactors()) {
                factorsNode.add(factor);
            }
            metadata = objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            log.error("Failed to serialize policy audit metadata: {}", e.getMessage(), e);
            metadata = "{\"error\":\"serialization_failed\"}";
        }
        auditService.record(tenantId, eventType, "POLICY_ENGINE", taskId, null, null, metadata);
    }
}
