package com.asea.approval;

import com.asea.audit.AuditEventType;
import com.asea.audit.AuditService;
import com.asea.common.exceptions.EntityNotFoundException;
import com.asea.common.exceptions.InvalidTransitionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ApprovalService Tests")
class ApprovalServiceTest {

    private static final String TENANT_ID = "tenant-alpha";
    private static final String TASK_ID = "ASEA-1234";
    private static final String ACTION = "DEPLOY";

    @Mock
    private ApprovalRepository approvalRepository;

    @Mock
    private AuditService auditService;

    private ApprovalService approvalService;

    @BeforeEach
    void setUp() {
        approvalService = new ApprovalService(approvalRepository, auditService);
    }

    @Test
    @DisplayName("requestApproval creates a new pending approval when none exists")
    void requestApproval_CreatesNew() {
        when(approvalRepository.findTopByTenantIdAndTaskIdAndStatusOrderByRequestedAtDesc(
                TENANT_ID, TASK_ID, "PENDING")).thenReturn(Optional.empty());
        when(approvalRepository.save(any(Approval.class))).thenAnswer(inv -> inv.getArgument(0));

        Approval approval = approvalService.requestApproval(TENANT_ID, TASK_ID, ACTION, "Critical deployment", "HIGH");

        assertThat(approval.getStatus()).isEqualTo("PENDING");
        assertThat(approval.getTenantId()).isEqualTo(TENANT_ID);
        assertThat(approval.getTaskId()).isEqualTo(TASK_ID);
        assertThat(approval.getActionType()).isEqualTo(ACTION);

        verify(auditService).record(eq(TENANT_ID), eq(AuditEventType.APPROVAL_REQUESTED),
                anyString(), eq(TASK_ID), any(), any(), any());
    }

    @Test
    @DisplayName("requestApproval is idempotent and reuses existing pending approval")
    void requestApproval_ReusesExistingPending() {
        Approval existing = new Approval();
        existing.setId(10L);
        existing.setTenantId(TENANT_ID);
        existing.setTaskId(TASK_ID);
        existing.setActionType(ACTION);
        existing.setStatus("PENDING");

        when(approvalRepository.findTopByTenantIdAndTaskIdAndStatusOrderByRequestedAtDesc(
                TENANT_ID, TASK_ID, "PENDING")).thenReturn(Optional.of(existing));

        Approval result = approvalService.requestApproval(TENANT_ID, TASK_ID, ACTION, "Second call", "HIGH");

        assertThat(result.getId()).isEqualTo(10L);
        verify(approvalRepository, never()).save(any());
    }

    @Test
    @DisplayName("approve transitions pending approval to APPROVED")
    void approve_Success() {
        Approval existing = new Approval();
        existing.setId(10L);
        existing.setTenantId(TENANT_ID);
        existing.setTaskId(TASK_ID);
        existing.setActionType(ACTION);
        existing.setStatus("PENDING");

        when(approvalRepository.findByIdAndTenantId(10L, TENANT_ID)).thenReturn(Optional.of(existing));
        when(approvalRepository.save(any(Approval.class))).thenAnswer(inv -> inv.getArgument(0));

        Approval result = approvalService.approve(TENANT_ID, 10L, "admin-reviewer", "Approved after inspection");

        assertThat(result.getStatus()).isEqualTo("APPROVED");
        assertThat(result.getDecisionBy()).isEqualTo("admin-reviewer");
        assertThat(result.getDecisionReason()).isEqualTo("Approved after inspection");
        assertThat(result.getDecidedAt()).isNotNull();

        verify(auditService).record(eq(TENANT_ID), eq(AuditEventType.APPROVAL_GRANTED),
                eq("admin-reviewer"), eq(TASK_ID), any(), any(), any());
    }

    @Test
    @DisplayName("reject transitions pending approval to REJECTED")
    void reject_Success() {
        Approval existing = new Approval();
        existing.setId(10L);
        existing.setTenantId(TENANT_ID);
        existing.setTaskId(TASK_ID);
        existing.setActionType(ACTION);
        existing.setStatus("PENDING");

        when(approvalRepository.findByIdAndTenantId(10L, TENANT_ID)).thenReturn(Optional.of(existing));
        when(approvalRepository.save(any(Approval.class))).thenAnswer(inv -> inv.getArgument(0));

        Approval result = approvalService.reject(TENANT_ID, 10L, "sec-team", "Policy violation in PR");

        assertThat(result.getStatus()).isEqualTo("REJECTED");
        assertThat(result.getDecisionBy()).isEqualTo("sec-team");
        assertThat(result.getDecisionReason()).isEqualTo("Policy violation in PR");

        verify(auditService).record(eq(TENANT_ID), eq(AuditEventType.APPROVAL_REJECTED),
                eq("sec-team"), eq(TASK_ID), any(), any(), any());
    }

    @Test
    @DisplayName("approve throws InvalidTransitionException when approval is already decided (terminal)")
    void approve_AlreadyDecided_ThrowsInvalidTransition() {
        Approval alreadyApproved = new Approval();
        alreadyApproved.setId(10L);
        alreadyApproved.setTenantId(TENANT_ID);
        alreadyApproved.setStatus("APPROVED");

        when(approvalRepository.findByIdAndTenantId(10L, TENANT_ID)).thenReturn(Optional.of(alreadyApproved));

        assertThatThrownBy(() -> approvalService.approve(TENANT_ID, 10L, "user", "ok"))
                .isInstanceOf(InvalidTransitionException.class)
                .hasMessageContaining("already decided");
    }

    @Test
    @DisplayName("findValidApproval returns empty if approval is expired")
    void findValidApproval_ExpiredReturnsEmpty() {
        Approval expired = new Approval();
        expired.setId(10L);
        expired.setTenantId(TENANT_ID);
        expired.setTaskId(TASK_ID);
        expired.setActionType(ACTION);
        expired.setStatus("APPROVED");
        expired.setExpiresAt(LocalDateTime.now().minusHours(1)); // expired

        when(approvalRepository.findTopByTenantIdAndTaskIdAndActionTypeAndStatusOrderByDecidedAtDesc(
                TENANT_ID, TASK_ID, ACTION, "APPROVED")).thenReturn(Optional.of(expired));

        Optional<Approval> valid = approvalService.findValidApproval(TENANT_ID, TASK_ID, ACTION);
        assertThat(valid).isEmpty();
    }
}
