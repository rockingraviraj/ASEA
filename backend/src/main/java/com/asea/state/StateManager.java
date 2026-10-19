package com.asea.state;

import com.asea.approval.ApprovalRepository;
import com.asea.audit.AuditEventType;
import com.asea.audit.AuditService;
import com.asea.common.exceptions.EntityNotFoundException;
import com.asea.common.exceptions.InvalidTransitionException;
import com.asea.common.exceptions.PermissionDeniedException;
import com.asea.task.Task;
import com.asea.task.TaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * Central State Manager for task lifecycle transitions.
 *
 * <p>Enforces:
 * <ul>
 *   <li>Tenant-scoped task retrieval</li>
 *   <li>Formal state validation against {@link StateGraph}</li>
 *   <li>Optimistic concurrency control via {@code @Version} on {@link Task}</li>
 *   <li>Rejection of transitions on terminal states (COMPLETED, FAILED, ESCALATED)</li>
 *   <li>Restriction of internal states to internal Orchestrator authority</li>
 *   <li>Verification of prerequisites before reaching terminal state COMPLETED</li>
 *   <li>Consistent history logging and business audit recording</li>
 * </ul>
 * </p>
 */
@Service
public class StateManager {

    private static final Logger log = LoggerFactory.getLogger(StateManager.class);

    private static final Set<TaskState> INTERNAL_ORCHESTRATOR_STATES = Set.of(
            TaskState.UNDERSTANDING,
            TaskState.ANALYZING,
            TaskState.DIAGNOSING,
            TaskState.PLANNING,
            TaskState.IMPLEMENTING,
            TaskState.TESTING,
            TaskState.VALIDATING,
            TaskState.RECOVERING,
            TaskState.REVIEWING,
            TaskState.SECURITY_CHECK,
            TaskState.CI_VALIDATION,
            TaskState.PR_READY,
            TaskState.HUMAN_REVIEW,
            TaskState.COMPLETED
    );

    private final TaskRepository taskRepository;
    private final StateTransitionRepository stateTransitionRepository;
    private final AuditService auditService;
    private final ApprovalRepository approvalRepository;

    public StateManager(TaskRepository taskRepository,
                        StateTransitionRepository stateTransitionRepository,
                        AuditService auditService,
                        ApprovalRepository approvalRepository) {
        this.taskRepository = taskRepository;
        this.stateTransitionRepository = stateTransitionRepository;
        this.auditService = auditService;
        this.approvalRepository = approvalRepository;
    }

    /**
     * Executes a state transition with authority verification.
     *
     * @param tenantId            verified tenant ID
     * @param taskId              task ID
     * @param toState             requested target state
     * @param trigger             event trigger description
     * @param reason              justification
     * @param isInternalAuthority true if initiated by internal Orchestrator/Engine; false for external API calls
     * @return updated and persisted Task
     */
    @Transactional
    public Task transition(String tenantId,
                           String taskId,
                           TaskState toState,
                           String trigger,
                           String reason,
                           boolean isInternalAuthority) {
        // Enforce boundary: external API callers cannot force internal Orchestrator lifecycle states
        if (!isInternalAuthority && INTERNAL_ORCHESTRATOR_STATES.contains(toState)) {
            log.warn("Unauthorized external transition attempt to internal state: taskId={} toState={} tenantId={}",
                    taskId, toState, tenantId);
            auditService.record(tenantId, AuditEventType.INVALID_TRANSITION_ATTEMPTED,
                    "EXTERNAL_CALLER", taskId, null, toState,
                    "{\"error\":\"External callers cannot force internal state: " + toState + "\"}");
            throw new PermissionDeniedException(
                    "External callers cannot directly transition task to internal lifecycle state: " + toState
                    + ". Internal states are reserved for Orchestrator execution.");
        }

        Task task = taskRepository.findByTaskIdAndTenantId(taskId, tenantId)
                .orElseThrow(() -> new EntityNotFoundException("Task not found: " + taskId));

        TaskState fromState = task.getCurrentState();
        if (fromState.isTerminal()) {
            throw new InvalidTransitionException("Task is already in terminal state: " + fromState);
        }

        // Validate graph legality
        StateGraph.validate(fromState, toState);

        // Verification before COMPLETED: ensure no pending human approvals block completion
        if (toState == TaskState.COMPLETED) {
            long pendingApprovals = approvalRepository.findTopByTenantIdAndTaskIdAndStatusOrderByRequestedAtDesc(
                    tenantId, taskId, "PENDING").stream().count();
            if (pendingApprovals > 0) {
                throw new InvalidTransitionException(
                        "Cannot transition to COMPLETED: task " + taskId + " has unresolved pending human approvals.");
            }
        }

        task.setCurrentState(toState);
        task.setStatus(toState);
        if (toState == TaskState.COMPLETED) {
            task.setCompletedAt(LocalDateTime.now());
        }

        Task saved = taskRepository.save(task);

        recordTransitionHistory(taskId, tenantId, fromState, toState, trigger, reason);

        auditService.record(tenantId, AuditEventType.STATE_CHANGED,
                isInternalAuthority ? "ORCHESTRATOR" : "USER", taskId, fromState, toState, null);

        log.info("Task state transitioned: taskId={} from={} to={} trigger={} authority={}",
                taskId, fromState, toState, trigger, isInternalAuthority ? "INTERNAL" : "EXTERNAL");
        return saved;
    }

    /**
     * Cancels a task safely, transitioning it to FAILED with trigger USER_CANCEL.
     */
    @Transactional
    public Task cancelTask(String tenantId, String taskId, String reason) {
        return transition(tenantId, taskId, TaskState.FAILED, "USER_CANCEL",
                reason != null ? reason : "Task cancelled by user", false);
    }

    private void recordTransitionHistory(String taskId, String tenantId, TaskState from, TaskState to,
                                         String trigger, String reason) {
        StateTransition st = new StateTransition();
        st.setTaskId(taskId);
        st.setTenantId(tenantId);
        st.setFromState(from);
        st.setToState(to);
        st.setTrigger(trigger);
        st.setReason(reason);
        st.setCreatedAt(LocalDateTime.now());
        stateTransitionRepository.save(st);
    }
}
