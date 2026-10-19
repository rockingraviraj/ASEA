package com.asea.state;

import com.asea.approval.Approval;
import com.asea.approval.ApprovalRepository;
import com.asea.audit.AuditEventType;
import com.asea.audit.AuditService;
import com.asea.common.exceptions.EntityNotFoundException;
import com.asea.common.exceptions.InvalidTransitionException;
import com.asea.common.exceptions.PermissionDeniedException;
import com.asea.task.Task;
import com.asea.task.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("StateManager Central Transition Enforcement Tests")
class StateManagerTest {

    private static final String TENANT_ID = "tenant-alpha";
    private static final String TASK_ID = "ASEA-1234";

    @Mock
    private TaskRepository taskRepository;

    @Mock
    private StateTransitionRepository stateTransitionRepository;

    @Mock
    private AuditService auditService;

    @Mock
    private ApprovalRepository approvalRepository;

    private StateManager stateManager;

    @BeforeEach
    void setUp() {
        stateManager = new StateManager(taskRepository, stateTransitionRepository, auditService, approvalRepository);
    }

    @Test
    @DisplayName("External callers cannot force internal Orchestrator states directly")
    void transition_ExternalCaller_InternalStateBlocked() {
        assertThatThrownBy(() -> stateManager.transition(
                TENANT_ID, TASK_ID, TaskState.UNDERSTANDING, "TRIGGER", "reason", false))
                .isInstanceOf(PermissionDeniedException.class)
                .hasMessageContaining("External callers cannot directly transition task to internal lifecycle state");

        verify(taskRepository, never()).save(any());
        verify(auditService).record(eq(TENANT_ID), eq(AuditEventType.INVALID_TRANSITION_ATTEMPTED),
                eq("EXTERNAL_CALLER"), eq(TASK_ID), any(), eq(TaskState.UNDERSTANDING), any());
    }

    @Test
    @DisplayName("Internal authority can transition task along legal StateGraph edges")
    void transition_InternalAuthority_LegalTransition_Success() {
        Task task = createTestTask(TaskState.CREATED);
        when(taskRepository.findByTaskIdAndTenantId(TASK_ID, TENANT_ID)).thenReturn(Optional.of(task));
        when(taskRepository.save(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));

        Task result = stateManager.transition(
                TENANT_ID, TASK_ID, TaskState.UNDERSTANDING, "START_DISCOVERY", "Valid step", true);

        assertThat(result.getCurrentState()).isEqualTo(TaskState.UNDERSTANDING);
        assertThat(result.getStatus()).isEqualTo(TaskState.UNDERSTANDING);

        verify(stateTransitionRepository).save(any(StateTransition.class));
        verify(auditService).record(eq(TENANT_ID), eq(AuditEventType.STATE_CHANGED),
                eq("ORCHESTRATOR"), eq(TASK_ID), eq(TaskState.CREATED), eq(TaskState.UNDERSTANDING), any());
    }

    @Test
    @DisplayName("Transition on terminal state is rejected")
    void transition_OnTerminalState_ThrowsInvalidTransition() {
        Task task = createTestTask(TaskState.COMPLETED);
        when(taskRepository.findByTaskIdAndTenantId(TASK_ID, TENANT_ID)).thenReturn(Optional.of(task));

        assertThatThrownBy(() -> stateManager.transition(
                TENANT_ID, TASK_ID, TaskState.RECOVERING, "TRIGGER", "reason", true))
                .isInstanceOf(InvalidTransitionException.class)
                .hasMessageContaining("already in terminal state");

        verify(taskRepository, never()).save(any());
    }

    @Test
    @DisplayName("Illegal transition according to StateGraph is rejected")
    void transition_IllegalEdge_ThrowsInvalidTransition() {
        Task task = createTestTask(TaskState.CREATED);
        when(taskRepository.findByTaskIdAndTenantId(TASK_ID, TENANT_ID)).thenReturn(Optional.of(task));

        // CREATED -> TESTING is illegal in StateGraph
        assertThatThrownBy(() -> stateManager.transition(
                TENANT_ID, TASK_ID, TaskState.TESTING, "TRIGGER", "reason", true))
                .isInstanceOf(InvalidTransitionException.class)
                .hasMessageContaining("Illegal state transition");

        verify(taskRepository, never()).save(any());
    }

    @Test
    @DisplayName("Transition to COMPLETED is rejected if pending human approvals exist")
    void transition_ToCompleted_BlockedByPendingApprovals() {
        Task task = createTestTask(TaskState.HUMAN_REVIEW);
        when(taskRepository.findByTaskIdAndTenantId(TASK_ID, TENANT_ID)).thenReturn(Optional.of(task));

        Approval pendingApproval = new Approval();
        pendingApproval.setStatus("PENDING");
        when(approvalRepository.findTopByTenantIdAndTaskIdAndStatusOrderByRequestedAtDesc(
                TENANT_ID, TASK_ID, "PENDING")).thenReturn(Optional.of(pendingApproval));

        assertThatThrownBy(() -> stateManager.transition(
                TENANT_ID, TASK_ID, TaskState.COMPLETED, "PR_MERGED", "Done", true))
                .isInstanceOf(InvalidTransitionException.class)
                .hasMessageContaining("has unresolved pending human approvals");

        verify(taskRepository, never()).save(any());
    }

    @Test
    @DisplayName("cancelTask safely transitions active task to FAILED")
    void cancelTask_Success() {
        Task task = createTestTask(TaskState.UNDERSTANDING);
        when(taskRepository.findByTaskIdAndTenantId(TASK_ID, TENANT_ID)).thenReturn(Optional.of(task));
        when(taskRepository.save(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));

        Task cancelled = stateManager.cancelTask(TENANT_ID, TASK_ID, "User cancelled");

        assertThat(cancelled.getCurrentState()).isEqualTo(TaskState.FAILED);
        assertThat(cancelled.getStatus()).isEqualTo(TaskState.FAILED);
        verify(auditService).record(eq(TENANT_ID), eq(AuditEventType.STATE_CHANGED),
                eq("USER"), eq(TASK_ID), eq(TaskState.UNDERSTANDING), eq(TaskState.FAILED), any());
    }

    @Test
    @DisplayName("Cross-tenant task transition throws EntityNotFoundException without leaking existence")
    void transition_CrossTenant_ThrowsEntityNotFound() {
        when(taskRepository.findByTaskIdAndTenantId(TASK_ID, TENANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> stateManager.cancelTask(TENANT_ID, TASK_ID, "User cancelled"))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining("Task not found");
    }

    private Task createTestTask(TaskState state) {
        Task t = new Task();
        t.setId(1L);
        t.setTenantId(TENANT_ID);
        t.setTaskId(TASK_ID);
        t.setCurrentState(state);
        t.setStatus(state);
        return t;
    }
}
