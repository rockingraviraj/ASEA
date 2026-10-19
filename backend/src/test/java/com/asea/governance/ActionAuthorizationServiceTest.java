package com.asea.governance;

import com.asea.approval.Approval;
import com.asea.approval.ApprovalService;
import com.asea.audit.AuditService;
import com.asea.budget.BudgetCheckResult;
import com.asea.budget.BudgetManager;
import com.asea.common.exceptions.ApprovalRequiredException;
import com.asea.common.exceptions.BudgetExceededException;
import com.asea.common.exceptions.PermissionDeniedException;
import com.asea.common.exceptions.PolicyViolationException;
import com.asea.permission.PermissionManager;
import com.asea.policy.PolicyDecision;
import com.asea.policy.PolicyEngine;
import com.asea.policy.RiskLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ActionAuthorizationService Gate Tests")
class ActionAuthorizationServiceTest {

    private static final String TENANT_ID = "tenant-alpha";
    private static final String TASK_ID = "ASEA-1234";
    private static final String ACTOR = "agent-developer";
    private static final String ACTION = "MODIFY_CODE";

    @Mock
    private PolicyEngine policyEngine;

    @Mock
    private PermissionManager permissionManager;

    @Mock
    private BudgetManager budgetManager;

    @Mock
    private ApprovalService approvalService;

    @Mock
    private AuditService auditService;

    private ActionAuthorizationService authorizationService;

    @BeforeEach
    void setUp() {
        authorizationService = new ActionAuthorizationService(
                policyEngine, permissionManager, budgetManager, approvalService, auditService);
    }

    @Test
    @DisplayName("Stage 1: Policy denial blocks execution immediately")
    void authorize_PolicyDenial_BlocksExecution() {
        PolicyDecision policyDecision = new PolicyDecision(false, false, RiskLevel.HIGH, "Denied by org policy", List.of());
        when(policyEngine.evaluate(eq(TENANT_ID), eq(ACTION), eq(TASK_ID), anyList(), eq(1)))
                .thenReturn(policyDecision);

        assertThatThrownBy(() -> authorizationService.authorizeAction(
                TENANT_ID, TASK_ID, ACTOR, ACTION, List.of("file.txt"), 1, 100, BigDecimal.ZERO))
                .isInstanceOf(PolicyViolationException.class)
                .hasMessageContaining("Denied by org policy");
    }

    @Test
    @DisplayName("Stage 2: Permission check failure blocks execution independently")
    void authorize_PermissionDenied_BlocksExecution() {
        PolicyDecision policyDecision = new PolicyDecision(true, false, RiskLevel.LOW, "Policy allows", List.of());
        when(policyEngine.evaluate(anyString(), anyString(), anyString(), anyList(), eq(0)))
                .thenReturn(policyDecision);
        when(permissionManager.hasPermission(TENANT_ID, ACTOR, ACTION)).thenReturn(false);

        assertThatThrownBy(() -> authorizationService.authorizeAction(
                TENANT_ID, TASK_ID, ACTOR, ACTION, List.of(), 0, 0, BigDecimal.ZERO))
                .isInstanceOf(PermissionDeniedException.class)
                .hasMessageContaining("lacks permission");
    }

    @Test
    @DisplayName("Stage 3: Budget check failure blocks execution")
    void authorize_BudgetExceeded_BlocksExecution() {
        PolicyDecision policyDecision = new PolicyDecision(true, false, RiskLevel.LOW, "Policy allows", List.of());
        when(policyEngine.evaluate(anyString(), anyString(), anyString(), anyList(), eq(0)))
                .thenReturn(policyDecision);
        when(permissionManager.hasPermission(TENANT_ID, ACTOR, ACTION)).thenReturn(true);
        when(budgetManager.check(TENANT_ID, TASK_ID))
                .thenReturn(new BudgetCheckResult(false, "Token budget exhausted", 0, BigDecimal.ZERO, 1));

        assertThatThrownBy(() -> authorizationService.authorizeAction(
                TENANT_ID, TASK_ID, ACTOR, ACTION, List.of(), 0, 0, BigDecimal.ZERO))
                .isInstanceOf(BudgetExceededException.class)
                .hasMessageContaining("Token budget exhausted");
    }

    @Test
    @DisplayName("Stage 4: Approval required produces approval request and throws ApprovalRequiredException")
    void authorize_ApprovalRequired_RequestsApprovalAndThrows() {
        PolicyDecision policyDecision = new PolicyDecision(true, true, RiskLevel.CRITICAL, "Critical modification", List.of());
        when(policyEngine.evaluate(anyString(), anyString(), anyString(), anyList(), eq(1)))
                .thenReturn(policyDecision);
        when(permissionManager.hasPermission(TENANT_ID, ACTOR, ACTION)).thenReturn(true);
        when(budgetManager.check(TENANT_ID, TASK_ID))
                .thenReturn(new BudgetCheckResult(true, "OK", 50000, BigDecimal.valueOf(5.00), 2));
        when(approvalService.findValidApproval(TENANT_ID, TASK_ID, ACTION))
                .thenReturn(Optional.empty());

        Approval pendingApproval = new Approval();
        pendingApproval.setId(99L);
        pendingApproval.setStatus("PENDING");
        when(approvalService.requestApproval(eq(TENANT_ID), eq(TASK_ID), eq(ACTION), anyString(), eq("CRITICAL")))
                .thenReturn(pendingApproval);

        assertThatThrownBy(() -> authorizationService.authorizeAction(
                TENANT_ID, TASK_ID, ACTOR, ACTION, List.of("auth/Auth.java"), 1, 100, BigDecimal.ZERO))
                .isInstanceOf(ApprovalRequiredException.class)
                .hasMessageContaining("Human approval is required");

        verify(approvalService).requestApproval(eq(TENANT_ID), eq(TASK_ID), eq(ACTION), anyString(), eq("CRITICAL"));
    }

    @Test
    @DisplayName("Stage 4: Valid pre-existing approval authorizes execution when policy/permission/budget pass")
    void authorize_WithValidApproval_AuthorizesExecution() {
        PolicyDecision policyDecision = new PolicyDecision(true, true, RiskLevel.CRITICAL, "Critical modification", List.of());
        when(policyEngine.evaluate(anyString(), anyString(), anyString(), anyList(), eq(1)))
                .thenReturn(policyDecision);
        when(permissionManager.hasPermission(TENANT_ID, ACTOR, ACTION)).thenReturn(true);
        when(budgetManager.check(TENANT_ID, TASK_ID))
                .thenReturn(new BudgetCheckResult(true, "OK", 50000, BigDecimal.valueOf(5.00), 2));

        Approval validApproval = new Approval();
        validApproval.setId(99L);
        validApproval.setStatus("APPROVED");
        validApproval.setDecisionBy("human-lead");
        when(approvalService.findValidApproval(TENANT_ID, TASK_ID, ACTION))
                .thenReturn(Optional.of(validApproval));

        ActionAuthorizationResult result = authorizationService.authorizeAction(
                TENANT_ID, TASK_ID, ACTOR, ACTION, List.of("auth/Auth.java"), 1, 100, BigDecimal.ZERO);

        assertThat(result.authorized()).isTrue();
        assertThat(result.requiresApproval()).isTrue();
        assertThat(result.approvalId()).isEqualTo(99L);
    }
}
