package com.asea.permission;

import com.asea.audit.AuditEventType;
import com.asea.audit.AuditService;
import com.asea.common.exceptions.PermissionDeniedException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Permission Manager — answers "Is this specific actor allowed to perform this operation?"
 *
 * <p>Kept strictly separate from the Policy Engine:
 * <ul>
 *   <li>Policy Engine: "Is this action category allowed by rule?" (action-centric)</li>
 *   <li>Permission Manager: "Does this specific agent/actor have a grant?" (actor-centric)</li>
 * </ul>
 * </p>
 *
 * <p>Default permission behaviour: if no explicit permission is found, the operation is DENIED.
 * Permission must be explicitly granted, never implicitly assumed.</p>
 */
@Service
public class PermissionManager {

    private static final Logger log = LoggerFactory.getLogger(PermissionManager.class);

    // Built-in default permissions for well-known agent types
    private static final java.util.Map<String, java.util.Set<String>> DEFAULT_PERMISSIONS =
            java.util.Map.of(
                    AgentTypes.CODING_AGENT, java.util.Set.of("READ_FILE", "WRITE_FILE", "READ_CONTEXT"),
                    AgentTypes.REVIEW_AGENT, java.util.Set.of("READ_FILE", "READ_CONTEXT"),
                    AgentTypes.TEST_AGENT, java.util.Set.of("READ_FILE", "EXECUTE_TESTS", "READ_CONTEXT"),
                    AgentTypes.PR_AGENT, java.util.Set.of("READ_FILE", "CREATE_PR", "READ_CONTEXT"),
                    AgentTypes.SECURITY_AGENT, java.util.Set.of("READ_FILE", "EXECUTE_SCAN", "READ_CONTEXT"),
                    AgentTypes.ISSUE_ANALYSIS_AGENT, java.util.Set.of("READ_ISSUE", "READ_CONTEXT"),
                    AgentTypes.ROOT_CAUSE_AGENT, java.util.Set.of("READ_FILE", "READ_LOGS", "READ_CONTEXT"),
                    AgentTypes.PLANNING_AGENT, java.util.Set.of("READ_FILE", "READ_CONTEXT"),
                    AgentTypes.CI_AGENT, java.util.Set.of("READ_CI", "TRIGGER_CI", "READ_CONTEXT"),
                    AgentTypes.DEPENDENCY_AGENT, java.util.Set.of("READ_FILE", "READ_DEPENDENCIES", "READ_CONTEXT")
            );

    private final PermissionRepository permissionRepository;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public PermissionManager(PermissionRepository permissionRepository,
                             AuditService auditService,
                             ObjectMapper objectMapper) {
        this.permissionRepository = permissionRepository;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    /**
     * Checks whether the given actor has permission to perform the operation.
     *
     * @param tenantId  tenant context
     * @param actorType agent type (use {@link AgentTypes} constants)
     * @param operation the operation being requested
     * @param taskId    task context for audit
     * @return true if permitted
     */
    @Transactional(readOnly = true)
    public boolean isPermitted(String tenantId, String actorType, String operation, String taskId) {
        // Check explicit DB grant first (override default)
        Optional<Permission> explicit = permissionRepository
                .findByTenantIdAndActorTypeAndOperation(tenantId, actorType, operation);
        if (explicit.isPresent()) {
            boolean permitted = explicit.get().isAllowed();
            audit(tenantId, actorType, operation, taskId, permitted);
            return permitted;
        }

        // Fall back to built-in defaults
        java.util.Set<String> defaults = DEFAULT_PERMISSIONS.get(actorType);
        boolean permitted = defaults != null && defaults.contains(operation);
        audit(tenantId, actorType, operation, taskId, permitted);

        if (!permitted) {
            log.warn("Permission DENIED actorType={} operation={} taskId={} tenantId={}",
                    actorType, operation, taskId, tenantId);
        }
        return permitted;
    }

    /**
     * Enforces permission — throws {@link PermissionDeniedException} if denied.
     */
    public void enforce(String tenantId, String actorType, String operation, String taskId) {
        if (!isPermitted(tenantId, actorType, operation, taskId)) {
            throw new PermissionDeniedException(
                    actorType + " is not permitted to perform: " + operation);
        }
    }

    /**
     * Checks if an agent type is read-only (cannot modify files).
     */
    public boolean isReadOnly(String actorType) {
        return AgentTypes.REVIEW_AGENT.equals(actorType)
                || AgentTypes.ISSUE_ANALYSIS_AGENT.equals(actorType)
                || AgentTypes.ROOT_CAUSE_AGENT.equals(actorType)
                || AgentTypes.PLANNING_AGENT.equals(actorType);
    }

    // -------------------------------------------------------------------------

    private void audit(String tenantId, String actorType, String operation, String taskId, boolean granted) {
        AuditEventType eventType = granted ? AuditEventType.PERMISSION_GRANTED : AuditEventType.PERMISSION_DENIED;
        String metadata;
        try {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("actorType", actorType);
            node.put("operation", operation);
            metadata = objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            log.error("Failed to serialize permission audit metadata: {}", e.getMessage(), e);
            metadata = "{\"error\":\"serialization_failed\"}";
        }
        auditService.record(tenantId, eventType, actorType, taskId, null, null, metadata);
    }
}
