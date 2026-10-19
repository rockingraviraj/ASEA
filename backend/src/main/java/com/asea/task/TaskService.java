package com.asea.task;

import com.asea.audit.AuditEventType;
import com.asea.audit.AuditService;
import com.asea.budget.BudgetManager;
import com.asea.common.exceptions.EntityNotFoundException;
import com.asea.common.exceptions.InvalidTransitionException;
import com.asea.security.TenantResolver;
import com.asea.state.StateGraph;
import com.asea.state.StateTransition;
import com.asea.state.StateTransitionRepository;
import com.asea.state.TaskState;
import com.asea.task.dto.TaskCreateRequest;
import com.asea.task.dto.TaskHistoryResponse;
import com.asea.task.dto.TaskResponse;
import com.asea.task.dto.TaskTransitionRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Manages the lifecycle of {@link Task} entities.
 *
 * <p>All state transitions are validated through {@link StateGraph} and
 * recorded in {@link StateTransition} for full auditability.</p>
 *
 * <p>Every new task receives an initialised {@link com.asea.budget.Budget} record
 * at creation time. Budget is required by the architecture: operations must not
 * proceed when limits are exhausted.</p>
 *
 * <p><b>Tenant isolation:</b> All tenant identity is derived from the authenticated
 * {@link com.asea.security.AseaPrincipal} via {@link TenantResolver}. Caller-supplied
 * tenant IDs are treated as assertions only and validated against the resolved tenant.
 * Mismatches result in 403 Forbidden. Task lookup by ID always uses the verified tenant
 * scope so cross-tenant data never leaks.</p>
 */
@Service
public class TaskService {

    private static final Logger log = LoggerFactory.getLogger(TaskService.class);

    private final TaskRepository taskRepository;
    private final StateTransitionRepository stateTransitionRepository;
    private final AuditService auditService;
    private final BudgetManager budgetManager;
    private final TenantResolver tenantResolver;
    private final com.asea.state.StateManager stateManager;

    public TaskService(TaskRepository taskRepository,
                       StateTransitionRepository stateTransitionRepository,
                       AuditService auditService,
                       BudgetManager budgetManager,
                       TenantResolver tenantResolver,
                       com.asea.state.StateManager stateManager) {
        this.taskRepository = taskRepository;
        this.stateTransitionRepository = stateTransitionRepository;
        this.auditService = auditService;
        this.budgetManager = budgetManager;
        this.tenantResolver = tenantResolver;
        this.stateManager = stateManager;
    }

    /**
     * Creates a new task and initialises its budget record atomically.
     *
     * <p>Budget initialisation happens in the same transaction as task creation.
     * If budget initialisation fails, the task is not persisted.</p>
     *
     * <p>Tenant is always derived from the authenticated principal — never from the request body.</p>
     */
    @Transactional
    public TaskResponse createTask(TaskCreateRequest request) {
        // Authoritative tenant from authenticated principal.
        // If caller supplied a legacy tenant assertion in the body, validate it:
        // mismatch throws PermissionDeniedException (HTTP 403 Forbidden).
        String tenantId = tenantResolver.validateAndResolveTenant(request.tenantAssertion());

        String taskId = "ASEA-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        Task task = new Task();
        task.setTenantId(tenantId);
        task.setTaskId(taskId);
        task.setRepositoryId(request.repositoryId());
        task.setTitle(request.title());
        task.setDescription(request.description());
        task.setIssueUrl(request.issueUrl());
        task.setTrigger(request.trigger());
        task.setTaskType(request.taskType());
        task.setPriority(request.priority() != null ? request.priority() : 0);
        task.setStatus(TaskState.CREATED);
        task.setCurrentState(TaskState.CREATED);
        task.setMaxAttempts(request.maxAttempts() != null ? request.maxAttempts() : 3);
        task.setBudgetTokens(request.budgetTokens());
        task.setBudgetCost(request.budgetCost());
        task.setMaxExecutionMs(request.maxExecutionMs());
        // Metadata must be valid JSON or null — validated at the service layer
        if (request.metadata() != null) {
            validateJsonString(request.metadata(), "metadata");
        }
        task.setMetadata(request.metadata());

        Task saved = taskRepository.save(task);

        // Architecture requirement: every task receives a persisted budget.
        // initializeBudget is called within this transaction so failure is atomic.
        budgetManager.initializeBudget(
                saved.getTenantId(),
                saved.getTaskId(),
                saved.getBudgetTokens(),
                saved.getBudgetCost(),
                saved.getMaxAttempts(),
                saved.getMaxExecutionMs());

        recordTransition(saved.getTaskId(), saved.getTenantId(), null, TaskState.CREATED,
                "TASK_CREATED", "Task created via API");
        auditService.record(saved.getTenantId(), AuditEventType.TASK_CREATED,
                "SYSTEM", saved.getTaskId(), null, TaskState.CREATED, null);

        log.info("Task created taskId={} tenantId={} type={}", taskId, tenantId, task.getTaskType());
        return toResponse(saved);
    }

    /**
     * Retrieves a task by its task ID, enforcing tenant ownership via a tenant-scoped
     * repository query. Cross-tenant task IDs return 404 (non-disclosing).
     *
     * <p>The optional {@code assertedTenantId} parameter is treated as an assertion only:
     * if supplied it must match the authenticated tenant, otherwise 403 is returned.
     * The authenticated tenant is always the authoritative lookup scope.</p>
     *
     * @param taskId           the task's public identifier (e.g. "ASEA-1A2B3C4D")
     * @param assertedTenantId optional caller assertion — null means use authenticated tenant
     * @throws EntityNotFoundException  if the task does not exist within the authenticated tenant scope
     */
    @Transactional(readOnly = true)
    public TaskResponse getTask(String taskId, String assertedTenantId) {
        String tenantId = tenantResolver.validateAndResolveTenant(assertedTenantId);
        // Tenant-scoped lookup — never uses a global findByTaskId that could leak cross-tenant
        Task task = taskRepository.findByTaskIdAndTenantId(taskId, tenantId)
                .orElseThrow(() -> {
                    log.debug("Task not found or not accessible: taskId={} tenant={}", taskId, tenantId);
                    return new EntityNotFoundException("Task not found: " + taskId);
                });
        return toResponse(task);
    }

    /**
     * Lists tasks for the authenticated tenant.
     *
     * <p>The authenticated tenant is always the scope — callers cannot list tasks across tenants.
     * The optional {@code assertedTenantId} is validated as an assertion only.</p>
     */
    @Transactional(readOnly = true)
    public Page<TaskResponse> listTasks(String assertedTenantId, TaskState status, Pageable pageable) {
        String tenantId = tenantResolver.validateAndResolveTenant(assertedTenantId);
        Page<Task> page;
        if (status != null) {
            page = taskRepository.findByTenantIdAndStatus(tenantId, status, pageable);
        } else {
            page = taskRepository.findByTenantId(tenantId, pageable);
        }
        return page.map(this::toResponse);
    }

    /**
     * Returns the full state-transition history for a task, verifying tenant ownership
     * via a tenant-scoped lookup.
     */
    @Transactional(readOnly = true)
    public List<TaskHistoryResponse> getTaskHistory(String taskId, String assertedTenantId) {
        String tenantId = tenantResolver.validateAndResolveTenant(assertedTenantId);
        // Tenant-scoped lookup first
        taskRepository.findByTaskIdAndTenantId(taskId, tenantId)
                .orElseThrow(() -> {
                    log.debug("History not found or not accessible: taskId={} tenant={}", taskId, tenantId);
                    return new EntityNotFoundException("Task not found: " + taskId);
                });
        return stateTransitionRepository.findByTaskIdAndTenantIdOrderByCreatedAtAsc(taskId, tenantId)
                .stream()
                .map(st -> new TaskHistoryResponse(
                        st.getId(), st.getTaskId(), st.getFromState(), st.getToState(),
                        st.getTrigger(), st.getReason(), st.getCreatedAt()))
                .collect(Collectors.toList());
    }

    /**
     * Performs a validated state transition.
     *
     * <p>Enforces:</p>
     * <ul>
     *   <li>Tenant ownership via tenant-scoped repository lookup</li>
     *   <li>Task is not in a terminal state</li>
     *   <li>The requested transition is legal per the state graph</li>
     * </ul>
     *
     * <p>Uses optimistic locking via {@code @Version} on {@link Task} to prevent
     * concurrent requests from silently overwriting state.</p>
     */
    @Transactional
    public TaskResponse transitionTask(String taskId, String assertedTenantId, TaskTransitionRequest request) {
        String tenantId = tenantResolver.validateAndResolveTenant(assertedTenantId);
        // Delegate to centralized StateManager enforcing internal orchestrator state barriers
        Task saved = stateManager.transition(
                tenantId,
                taskId,
                request.toState(),
                request.trigger(),
                request.reason(),
                false);
        return toResponse(saved);
    }

    /**
     * Cancels a task upon caller request if permitted by the lifecycle state machine.
     */
    @Transactional
    public TaskResponse cancelTask(String taskId, String assertedTenantId, String reason) {
        String tenantId = tenantResolver.validateAndResolveTenant(assertedTenantId);
        Task saved = stateManager.cancelTask(tenantId, taskId, reason);
        return toResponse(saved);
    }

    /**
     * Internal transition method for Orchestrator and service-owned operations.
     */
    @Transactional
    public TaskResponse transitionInternal(String taskId, String tenantId, TaskState toState,
                                          String trigger, String reason) {
        Task saved = stateManager.transition(tenantId, taskId, toState, trigger, reason, true);
        return toResponse(saved);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void recordTransition(String taskId, String tenantId, TaskState from, TaskState to,
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

    /**
     * Validates that a string is valid JSON — rejects unsafe/malformed values
     * before they are stored in a JSONB column.
     *
     * @throws com.asea.common.exceptions.ValidationException if not valid JSON
     */
    private void validateJsonString(String json, String fieldName) {
        if (json == null) return;
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            mapper.readTree(json);
        } catch (Exception e) {
            throw new com.asea.common.exceptions.ValidationException(
                    fieldName + " must be valid JSON: " + e.getMessage());
        }
    }

    private TaskResponse toResponse(Task task) {
        return new TaskResponse(
                task.getId(), task.getTaskId(), task.getTenantId(), task.getRepositoryId(),
                task.getTitle(), task.getDescription(), task.getIssueUrl(), task.getTrigger(),
                task.getTaskType(), task.getPriority(), task.getStatus(), task.getCurrentState(),
                task.getAssignedAgent(), task.getBudgetTokens(), task.getBudgetCost(),
                task.getMaxAttempts(), task.getMaxExecutionMs(),
                task.getCreatedAt(), task.getUpdatedAt(), task.getCompletedAt(), task.getMetadata());
    }
}
