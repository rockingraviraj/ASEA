package com.asea.retry;

import com.asea.approval.Approval;
import com.asea.approval.ApprovalRepository;
import com.asea.audit.AuditService;
import com.asea.budget.BudgetCheckResult;
import com.asea.budget.BudgetManager;
import com.asea.state.TaskState;
import com.asea.task.Task;
import com.asea.task.TaskRepository;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("RetryManager Recovery and Bound Tests")
class RetryManagerTest {

    private static final String TENANT_ID = "tenant-alpha";
    private static final String TASK_ID = "ASEA-1234";

    @Mock
    private RetryAttemptRepository retryAttemptRepository;

    @Mock
    private TaskRepository taskRepository;

    @Mock
    private BudgetManager budgetManager;

    @Mock
    private ApprovalRepository approvalRepository;

    @Mock
    private AuditService auditService;

    private RetryManager retryManager;

    @BeforeEach
    void setUp() {
        retryManager = new RetryManager(
                retryAttemptRepository, taskRepository, budgetManager, approvalRepository, auditService);
    }

    @Test
    @DisplayName("classifyFailure correctly categorizes error types")
    void classifyFailure_CategorizesAccurately() {
        assertThat(retryManager.classifyFailure(new AssertionError("expected 1 but was 2")))
                .isEqualTo("TEST_FAILURE");
        assertThat(retryManager.classifyFailure(new java.util.concurrent.TimeoutException("timeout")))
                .isEqualTo("TIMEOUT");
        assertThat(retryManager.classifyFailure(new com.asea.common.exceptions.BudgetExceededException("budget")))
                .isEqualTo("BUDGET_EXCEEDED");
        assertThat(retryManager.classifyFailure(new com.asea.common.exceptions.PolicyViolationException("policy")))
                .isEqualTo("POLICY_VIOLATION");
        assertThat(retryManager.classifyFailure(new RuntimeException("syntax error on compilation")))
                .isEqualTo("COMPILATION_ERROR");
        assertThat(retryManager.classifyFailure(new RuntimeException("general bug")))
                .isEqualTo("RUNTIME_ERROR");
    }

    @Test
    @DisplayName("authorizeRetry authorizes first retry when task is non-terminal, budget is ok, and no approval pending")
    void authorizeRetry_FirstRetry_Authorized() {
        Task task = new Task();
        task.setTenantId(TENANT_ID);
        task.setTaskId(TASK_ID);
        task.setCurrentState(TaskState.TESTING);
        task.setStatus(TaskState.TESTING);
        task.setMaxAttempts(3);

        when(taskRepository.findByTaskIdAndTenantId(TASK_ID, TENANT_ID)).thenReturn(Optional.of(task));
        when(approvalRepository.findTopByTenantIdAndTaskIdAndStatusOrderByRequestedAtDesc(
                TENANT_ID, TASK_ID, "PENDING")).thenReturn(Optional.empty());
        when(budgetManager.check(TENANT_ID, TASK_ID))
                .thenReturn(new BudgetCheckResult(true, "OK", 50000, BigDecimal.TEN, 2));
        when(retryAttemptRepository.findByTaskIdAndTenantIdOrderByAttemptNumberAsc(TASK_ID, TENANT_ID))
                .thenReturn(List.of());

        RetryDecision decision = retryManager.authorizeRetry(TENANT_ID, TASK_ID, "TEST_FAILURE", "Assertion error");

        assertThat(decision.shouldRetry()).isTrue();
        assertThat(decision.action()).isEqualTo("RETRY");
        assertThat(decision.targetAttemptNumber()).isEqualTo(1);
    }

    @Test
    @DisplayName("authorizeRetry blocks retry and escalates if task is in terminal state")
    void authorizeRetry_TerminalTask_Denied() {
        Task task = new Task();
        task.setTenantId(TENANT_ID);
        task.setTaskId(TASK_ID);
        task.setCurrentState(TaskState.COMPLETED);
        task.setStatus(TaskState.COMPLETED);

        when(taskRepository.findByTaskIdAndTenantId(TASK_ID, TENANT_ID)).thenReturn(Optional.of(task));

        RetryDecision decision = retryManager.authorizeRetry(TENANT_ID, TASK_ID, "TEST_FAILURE", "reason");

        assertThat(decision.shouldRetry()).isFalse();
        assertThat(decision.action()).isEqualTo("TERMINAL");
    }

    @Test
    @DisplayName("authorizeRetry pauses retry if human approval is pending")
    void authorizeRetry_PendingApproval_Pauses() {
        Task task = new Task();
        task.setTenantId(TENANT_ID);
        task.setTaskId(TASK_ID);
        task.setCurrentState(TaskState.TESTING);
        task.setStatus(TaskState.TESTING);

        when(taskRepository.findByTaskIdAndTenantId(TASK_ID, TENANT_ID)).thenReturn(Optional.of(task));
        Approval pending = new Approval();
        pending.setStatus("PENDING");
        when(approvalRepository.findTopByTenantIdAndTaskIdAndStatusOrderByRequestedAtDesc(
                TENANT_ID, TASK_ID, "PENDING")).thenReturn(Optional.of(pending));

        RetryDecision decision = retryManager.authorizeRetry(TENANT_ID, TASK_ID, "TEST_FAILURE", "reason");

        assertThat(decision.shouldRetry()).isFalse();
        assertThat(decision.action()).isEqualTo("PENDING_APPROVAL");
    }

    @Test
    @DisplayName("authorizeRetry escalates when attempt limit is exhausted")
    void authorizeRetry_AttemptLimitExhausted_Escalates() {
        Task task = new Task();
        task.setTenantId(TENANT_ID);
        task.setTaskId(TASK_ID);
        task.setCurrentState(TaskState.TESTING);
        task.setStatus(TaskState.TESTING);
        task.setMaxAttempts(2);

        when(taskRepository.findByTaskIdAndTenantId(TASK_ID, TENANT_ID)).thenReturn(Optional.of(task));
        when(approvalRepository.findTopByTenantIdAndTaskIdAndStatusOrderByRequestedAtDesc(
                TENANT_ID, TASK_ID, "PENDING")).thenReturn(Optional.empty());
        when(budgetManager.check(TENANT_ID, TASK_ID))
                .thenReturn(new BudgetCheckResult(true, "OK", 50000, BigDecimal.TEN, 0));

        RetryAttempt a1 = new RetryAttempt();
        a1.setAttemptNumber(1);
        RetryAttempt a2 = new RetryAttempt();
        a2.setAttemptNumber(2);
        when(retryAttemptRepository.findByTaskIdAndTenantIdOrderByAttemptNumberAsc(TASK_ID, TENANT_ID))
                .thenReturn(List.of(a1, a2));

        RetryDecision decision = retryManager.authorizeRetry(TENANT_ID, TASK_ID, "TEST_FAILURE", "reason");

        assertThat(decision.shouldRetry()).isFalse();
        assertThat(decision.action()).isEqualTo("ESCALATE");
        assertThat(decision.reason()).contains("Maximum attempts");
    }

    @Test
    @DisplayName("authorizeRetry escalates upon detecting 3 repeated identical failures")
    void authorizeRetry_RepeatedIdenticalFailures_Escalates() {
        Task task = new Task();
        task.setTenantId(TENANT_ID);
        task.setTaskId(TASK_ID);
        task.setCurrentState(TaskState.TESTING);
        task.setStatus(TaskState.TESTING);
        task.setMaxAttempts(5);

        when(taskRepository.findByTaskIdAndTenantId(TASK_ID, TENANT_ID)).thenReturn(Optional.of(task));
        when(approvalRepository.findTopByTenantIdAndTaskIdAndStatusOrderByRequestedAtDesc(
                TENANT_ID, TASK_ID, "PENDING")).thenReturn(Optional.empty());
        when(budgetManager.check(TENANT_ID, TASK_ID))
                .thenReturn(new BudgetCheckResult(true, "OK", 50000, BigDecimal.TEN, 3));

        RetryAttempt a1 = new RetryAttempt();
        a1.setAttemptNumber(1);
        a1.setFailureSummary("NullPointer at line 42");
        RetryAttempt a2 = new RetryAttempt();
        a2.setAttemptNumber(2);
        a2.setFailureSummary("NullPointer at line 42");
        RetryAttempt a3 = new RetryAttempt();
        a3.setAttemptNumber(3);
        a3.setFailureSummary("NullPointer at line 42");
        when(retryAttemptRepository.findByTaskIdAndTenantIdOrderByAttemptNumberAsc(TASK_ID, TENANT_ID))
                .thenReturn(List.of(a1, a2, a3));

        RetryDecision decision = retryManager.authorizeRetry(TENANT_ID, TASK_ID, "TEST_FAILURE", "NullPointer at line 42");

        assertThat(decision.shouldRetry()).isFalse();
        assertThat(decision.action()).isEqualTo("ESCALATE");
        assertThat(decision.reason()).contains("Repeated identical failure");
    }
}
