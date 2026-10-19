package com.asea.policy;

import com.asea.audit.AuditLog;
import com.asea.audit.AuditService;
import com.asea.audit.AuditEventType;
import com.asea.common.exceptions.PolicyViolationException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

/**
 * Unit tests for {@link PolicyEngine}.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Allowed actions without database policy</li>
 *   <li>Actions denied by explicit DB policy</li>
 *   <li>Actions requiring approval for critical paths</li>
 *   <li>Sensitive and protected file paths</li>
 *   <li>Modification size thresholds</li>
 *   <li>Audit event emission</li>
 *   <li>enforce() throwing PolicyViolationException on DENY</li>
 * </ul>
 * </p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PolicyEngine")
class PolicyEngineTest {

    @Mock
    private PolicyRepository policyRepository;

    @Mock
    private AuditService auditService;

    private PolicyEngine policyEngine;

    @BeforeEach
    void setUp() {
        policyEngine = new PolicyEngine(policyRepository, auditService, new ObjectMapper());
        // Default: no explicit DB policy found
        when(policyRepository.findByTenantIdAndAction(anyString(), anyString()))
                .thenReturn(Optional.empty());
        // Audit service returns a stub
        when(auditService.record(anyString(), any(), anyString(), anyString(), any(), any(), anyString()))
                .thenReturn(new AuditLog());
    }

    // -------------------------------------------------------------------------
    // Allowed actions
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Action with no DB policy and non-sensitive path is ALLOWED at LOW risk")
    void noDbPolicyNonSensitivePath_AllowedLowRisk() {
        PolicyDecision decision = policyEngine.evaluate(
                "tenant-1", "READ_FILE", "ASEA-001",
                List.of("src/main/java/com/example/Foo.java"), 5);

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.requiresApproval()).isFalse();
        // src/main/ is HIGH_RISK_PATHS → expect HIGH risk
        assertThat(decision.riskLevel()).isEqualTo(RiskLevel.HIGH);
    }

    @Test
    @DisplayName("Action with empty file paths and zero modifications is LOW risk")
    void emptyPathsZeroModifications_LowRisk() {
        PolicyDecision decision = policyEngine.evaluate(
                "tenant-1", "READ_CONTEXT", "ASEA-001",
                List.of(), 0);

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.riskLevel()).isEqualTo(RiskLevel.LOW);
        assertThat(decision.requiresApproval()).isFalse();
    }

    // -------------------------------------------------------------------------
    // Denied actions
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Action denied by explicit DB policy returns DENY decision")
    void explicitDbPolicyDeny_ReturnsDeny() {
        Policy denyPolicy = buildPolicy("BLOCK_PROD_DEPLOY", "DEPLOY_PRODUCTION",
                RiskLevel.CRITICAL, false, false, null);
        when(policyRepository.findByTenantIdAndAction("tenant-1", "DEPLOY_PRODUCTION"))
                .thenReturn(Optional.of(denyPolicy));

        PolicyDecision decision = policyEngine.evaluate(
                "tenant-1", "DEPLOY_PRODUCTION", "ASEA-001", List.of(), 0);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.riskLevel()).isEqualTo(RiskLevel.CRITICAL);
    }

    @Test
    @DisplayName("enforce() throws PolicyViolationException when action is denied")
    void enforce_ThrowsOnDeny() {
        Policy denyPolicy = buildPolicy("BLOCK_ALL", "WRITE_PRODUCTION",
                RiskLevel.CRITICAL, false, false, null);
        when(policyRepository.findByTenantIdAndAction("tenant-1", "WRITE_PRODUCTION"))
                .thenReturn(Optional.of(denyPolicy));

        assertThatThrownBy(() -> policyEngine.enforce(
                "tenant-1", "WRITE_PRODUCTION", "ASEA-001", List.of(), 0))
                .isInstanceOf(PolicyViolationException.class);
    }

    @Test
    @DisplayName("enforce() does not throw when action is allowed")
    void enforce_NoThrowWhenAllowed() {
        assertThatNoException().isThrownBy(
                () -> policyEngine.enforce("tenant-1", "READ_CONTEXT", "ASEA-001", List.of(), 0));
    }

    // -------------------------------------------------------------------------
    // Critical / sensitive paths — approval required
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Action touching .github/workflows/ requires approval at CRITICAL risk")
    void githubWorkflowPath_CriticalApprovalRequired() {
        PolicyDecision decision = policyEngine.evaluate(
                "tenant-1", "WRITE_FILE", "ASEA-001",
                List.of(".github/workflows/deploy.yml"), 10);

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.requiresApproval()).isTrue();
        assertThat(decision.riskLevel()).isEqualTo(RiskLevel.CRITICAL);
        assertThat(decision.contributingFactors()).anyMatch(f -> f.contains(".github/workflows/"));
    }

    @Test
    @DisplayName("Action touching Dockerfile requires approval at CRITICAL risk")
    void dockerfilePath_CriticalApprovalRequired() {
        PolicyDecision decision = policyEngine.evaluate(
                "tenant-1", "WRITE_FILE", "ASEA-001",
                List.of("Dockerfile"), 5);

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.requiresApproval()).isTrue();
        assertThat(decision.riskLevel()).isEqualTo(RiskLevel.CRITICAL);
    }

    @Test
    @DisplayName("Action touching payment/ path requires approval at CRITICAL risk")
    void paymentPath_CriticalApprovalRequired() {
        PolicyDecision decision = policyEngine.evaluate(
                "tenant-1", "WRITE_FILE", "ASEA-001",
                List.of("src/main/java/com/example/payment/PaymentService.java"), 20);

        assertThat(decision.riskLevel()).isEqualTo(RiskLevel.CRITICAL);
        assertThat(decision.requiresApproval()).isTrue();
    }

    @Test
    @DisplayName("Action touching auth/ path requires approval at CRITICAL risk")
    void authPath_CriticalApprovalRequired() {
        PolicyDecision decision = policyEngine.evaluate(
                "tenant-1", "WRITE_FILE", "ASEA-001",
                List.of("src/main/java/com/example/auth/AuthService.java"), 15);

        assertThat(decision.riskLevel()).isEqualTo(RiskLevel.CRITICAL);
        assertThat(decision.requiresApproval()).isTrue();
    }

    // -------------------------------------------------------------------------
    // Modification thresholds
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Large modification (>500 lines) escalates to HIGH risk with approval")
    void largeModification_HighRiskApprovalRequired() {
        PolicyDecision decision = policyEngine.evaluate(
                "tenant-1", "WRITE_FILE", "ASEA-001",
                List.of("src/main/java/Foo.java"), 501);

        // src/main/ is HIGH, 501 lines keeps it HIGH (already HIGH)
        assertThat(decision.riskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(decision.requiresApproval()).isTrue();
        assertThat(decision.contributingFactors()).anyMatch(f -> f.contains("Large modification"));
    }

    @Test
    @DisplayName("Medium modification (100-500 lines) escalates to at least MEDIUM risk")
    void mediumModification_AtLeastMediumRisk() {
        // Non-sensitive path + medium modification
        PolicyDecision decision = policyEngine.evaluate(
                "tenant-1", "WRITE_FILE", "ASEA-001",
                List.of("docs/README.md"), 150);

        assertThat(decision.riskLevel().ordinal()).isGreaterThanOrEqualTo(RiskLevel.MEDIUM.ordinal());
        assertThat(decision.contributingFactors()).anyMatch(f -> f.contains("Medium modification"));
    }

    // -------------------------------------------------------------------------
    // Audit emission
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Allowed action emits POLICY_ALLOWED audit event")
    void allowedAction_EmitsAllowedAuditEvent() {
        policyEngine.evaluate("tenant-1", "READ_FILE", "ASEA-001", List.of(), 0);

        verify(auditService).record(eq("tenant-1"), eq(AuditEventType.POLICY_ALLOWED),
                anyString(), anyString(), any(), any(), anyString());
    }

    @Test
    @DisplayName("Denied action emits POLICY_DENIED audit event")
    void deniedAction_EmitsDeniedAuditEvent() {
        Policy denyPolicy = buildPolicy("DENY", "BLOCKED_ACTION", RiskLevel.CRITICAL, false, false, null);
        when(policyRepository.findByTenantIdAndAction("tenant-1", "BLOCKED_ACTION"))
                .thenReturn(Optional.of(denyPolicy));

        policyEngine.evaluate("tenant-1", "BLOCKED_ACTION", "ASEA-001", List.of(), 0);

        verify(auditService).record(eq("tenant-1"), eq(AuditEventType.POLICY_DENIED),
                anyString(), anyString(), any(), any(), anyString());
    }

    @Test
    @DisplayName("Approval-required action emits POLICY_APPROVAL_REQUIRED audit event")
    void approvalRequiredAction_EmitsApprovalAuditEvent() {
        Policy approvalPolicy = buildPolicy("NEED_APPROVAL", "DEPLOY", RiskLevel.HIGH, true, true, null);
        when(policyRepository.findByTenantIdAndAction("tenant-1", "DEPLOY"))
                .thenReturn(Optional.of(approvalPolicy));

        policyEngine.evaluate("tenant-1", "DEPLOY", "ASEA-001", List.of(), 0);

        verify(auditService).record(eq("tenant-1"), eq(AuditEventType.POLICY_APPROVAL_REQUIRED),
                anyString(), anyString(), any(), any(), anyString());
    }

    // -------------------------------------------------------------------------
    // Policy/Permission independence & Enforcement
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("PolicyEngine operates independently — does not check actor identity")
    void policyEngineDoesNotCheckActorIdentity() {
        PolicyDecision d1 = policyEngine.evaluate("tenant-1", "READ_FILE", "ASEA-001", List.of(), 0);
        PolicyDecision d2 = policyEngine.evaluate("tenant-1", "READ_FILE", "ASEA-001", List.of(), 0);
        assertThat(d1.allowed()).isEqualTo(d2.allowed());
        assertThat(d1.riskLevel()).isEqualTo(d2.riskLevel());
    }

    @Test
    @DisplayName("enforce() throws PolicyViolationException when allowed is false")
    void enforce_ThrowsPolicyViolationException_WhenDisallowed() {
        PolicyDecision denial = new PolicyDecision(false, false, RiskLevel.HIGH, "Prohibited", List.of());
        assertThatThrownBy(() -> policyEngine.enforce(denial))
                .isInstanceOf(PolicyViolationException.class)
                .hasMessageContaining("Prohibited");
    }

    @Test
    @DisplayName("enforce() throws ApprovalRequiredException when requiresApproval is true even if allowed is true")
    void enforce_ThrowsApprovalRequiredException_WhenApprovalRequired() {
        PolicyDecision approvalRequired = new PolicyDecision(true, true, RiskLevel.CRITICAL, "Critical operation", List.of());
        assertThatThrownBy(() -> policyEngine.enforce(approvalRequired))
                .isInstanceOf(com.asea.common.exceptions.ApprovalRequiredException.class)
                .hasMessageContaining("Human approval is required");
    }

    @Test
    @DisplayName("enforce() succeeds without exception when allowed and no approval required")
    void enforce_Succeeds_WhenAllowedAndNoApproval() {
        PolicyDecision safe = new PolicyDecision(true, false, RiskLevel.LOW, "Safe operation", List.of());
        assertThatNoException().isThrownBy(() -> policyEngine.enforce(safe));
    }

    // -------------------------------------------------------------------------
    // Path Normalization & Traversal Security Tests
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Path traversal attempts with '..' are rejected")
    void evaluate_RejectsPathTraversal() {
        assertThatThrownBy(() -> policyEngine.evaluate(
                "tenant-1", "WRITE_FILE", "ASEA-001",
                List.of("src/main/../../etc/passwd"), 1))
                .isInstanceOf(PolicyViolationException.class)
                .hasMessageContaining("Path traversal detected");
    }

    @Test
    @DisplayName("Absolute paths starting with '/' are rejected")
    void evaluate_RejectsAbsolutePath() {
        assertThatThrownBy(() -> policyEngine.evaluate(
                "tenant-1", "WRITE_FILE", "ASEA-001",
                List.of("/etc/shadow"), 1))
                .isInstanceOf(PolicyViolationException.class)
                .hasMessageContaining("Absolute paths are not permitted");
    }

    @Test
    @DisplayName("Windows drive prefixes are rejected")
    void evaluate_RejectsDrivePrefix() {
        assertThatThrownBy(() -> policyEngine.evaluate(
                "tenant-1", "WRITE_FILE", "ASEA-001",
                List.of("C:\\Windows\\System32\\cmd.exe"), 1))
                .isInstanceOf(PolicyViolationException.class)
                .hasMessageContaining("Drive prefixes are not permitted");
    }

    @Test
    @DisplayName("Paths with NUL characters are rejected")
    void evaluate_RejectsNulCharacter() {
        assertThatThrownBy(() -> policyEngine.evaluate(
                "tenant-1", "WRITE_FILE", "ASEA-001",
                List.of("src/main/app.js\0.png"), 1))
                .isInstanceOf(PolicyViolationException.class)
                .hasMessageContaining("NUL character");
    }

    @Test
    @DisplayName("Backslash paths are normalized and recognized as critical for auth/ paths")
    void evaluate_NormalizesBackslashesAndDetectsAuth() {
        PolicyDecision decision = policyEngine.evaluate(
                "tenant-1", "MODIFY", "ASEA-001",
                List.of("src\\main\\auth\\SecurityHandler.java"), 1);

        assertThat(decision.requiresApproval()).isTrue();
        assertThat(decision.riskLevel()).isEqualTo(RiskLevel.CRITICAL);
    }

    @Test
    @DisplayName("Substring false positive: author/ does NOT trigger auth/ critical classification")
    void evaluate_DoesNotFalsePositiveOnAuthor() {
        PolicyDecision decision = policyEngine.evaluate(
                "tenant-1", "MODIFY", "ASEA-001",
                List.of("author/Profile.java"), 1);

        // author/ should NOT be flagged as CRITICAL auth/ path
        assertThat(decision.requiresApproval()).isFalse();
        assertThat(decision.riskLevel()).isNotEqualTo(RiskLevel.CRITICAL);
    }

    @Test
    @DisplayName("Normal README.md modification is low risk")
    void evaluate_ReadmeModificationIsLowRisk() {
        PolicyDecision decision = policyEngine.evaluate(
                "tenant-1", "MODIFY", "ASEA-001",
                List.of("README.md"), 2);

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.requiresApproval()).isFalse();
        assertThat(decision.riskLevel()).isEqualTo(RiskLevel.LOW);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Policy buildPolicy(String name, String action, RiskLevel risk,
                                boolean allowed, boolean requiresApproval, Integer maxModifications) {
        Policy p = new Policy();
        p.setName(name);
        p.setAction(action);
        p.setRiskLevel(risk);
        p.setAllowed(allowed);
        p.setRequiresApproval(requiresApproval);
        p.setMaxModifications(maxModifications);
        p.setTenantId("tenant-1");
        p.setCreatedAt(java.time.LocalDateTime.now());
        p.setUpdatedAt(java.time.LocalDateTime.now());
        return p;
    }
}
