package com.asea.task;

import com.asea.common.ApiResponse;
import com.asea.state.TaskState;
import com.asea.task.dto.TaskCreateRequest;
import com.asea.task.dto.TaskHistoryResponse;
import com.asea.task.dto.TaskResponse;
import com.asea.task.dto.TaskTransitionRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST controller for ASEA task management.
 *
 * <p>Exposes CRUD and lifecycle operations for tasks.
 * All business logic lives in {@link TaskService}; this class is a thin HTTP adapter.</p>
 *
 * <h2>Tenant isolation</h2>
 * <p>Tenant identity is <b>always</b> derived from the authenticated principal set by
 * {@link com.asea.security.ApiKeyAuthenticationFilter}. Caller-supplied {@code tenantId}
 * parameters (where accepted) are treated as <i>assertions only</i>:</p>
 * <ul>
 *   <li>Absent → normal operation using the authenticated tenant.</li>
 *   <li>Present and matching the authenticated tenant → accepted.</li>
 *   <li>Present and <em>not</em> matching the authenticated tenant → 403 Forbidden.</li>
 * </ul>
 * <p>A task ID that is not accessible within the authenticated tenant scope returns 404
 * (non-disclosing; does not reveal that the resource belongs to another tenant).</p>
 *
 * <h2>Authentication</h2>
 * <p>All endpoints under {@code /api/v1/**} require a valid {@code X-ASEA-API-Key} header.
 * Missing or invalid key → 401 Unauthorized.</p>
 */
@Validated
@RestController
@RequestMapping("/api/v1/tasks")
@Tag(name = "Tasks", description = "ASEA task management endpoints")
public class TaskController {

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
        summary = "Create a new task",
        description = "Submits a new engineering task to ASEA. " +
                      "Tenant ownership is strictly derived server-side from the authenticated API-key principal. " +
                      "An optional legacy tenantId in the request body is treated as an assertion only: " +
                      "if matching the authenticated tenant it is accepted; if mismatching it returns 403 Forbidden. " +
                      "It never determines or overrides ownership. " +
                      "A budget record is created atomically with the task.")
    public ApiResponse<TaskResponse> createTask(@Valid @RequestBody TaskCreateRequest request) {
        return ApiResponse.ok(taskService.createTask(request));
    }

    @GetMapping("/{taskId}")
    @Operation(
        summary = "Get task by ID",
        description = "Returns a task scoped to the authenticated tenant. " +
                      "Cross-tenant task IDs return 404 (non-disclosing). " +
                      "Optional tenantId parameter is an assertion: mismatch returns 403.")
    public ApiResponse<TaskResponse> getTask(
            @Parameter(description = "Task ID (e.g. ASEA-1A2B3C4D)") @PathVariable String taskId,
            @Parameter(description = "Optional tenant assertion — must match the authenticated tenant if supplied")
            @RequestParam(required = false) @Size(max = 50) String tenantId) {
        return ApiResponse.ok(taskService.getTask(taskId, tenantId));
    }

    @GetMapping
    @Operation(
        summary = "List tasks",
        description = "Returns paginated tasks for the authenticated tenant. " +
                      "Optional tenantId parameter is an assertion: mismatch returns 403.")
    public ApiResponse<Page<TaskResponse>> listTasks(
            @Parameter(description = "Optional tenant assertion — must match the authenticated tenant if supplied")
            @RequestParam(required = false) @Size(max = 50) String tenantId,
            @Parameter(description = "Optional status filter")
            @RequestParam(required = false) TaskState status,
            @PageableDefault(size = 20, sort = "createdAt") Pageable pageable) {
        return ApiResponse.ok(taskService.listTasks(tenantId, status, pageable));
    }

    @GetMapping("/{taskId}/history")
    @Operation(
        summary = "Get task state history",
        description = "Returns the full state-transition history for a task scoped to the authenticated tenant. " +
                      "Optional tenantId parameter is an assertion: mismatch returns 403.")
    public ApiResponse<List<TaskHistoryResponse>> getTaskHistory(
            @PathVariable String taskId,
            @Parameter(description = "Optional tenant assertion — must match the authenticated tenant if supplied")
            @RequestParam(required = false) @Size(max = 50) String tenantId) {
        return ApiResponse.ok(taskService.getTaskHistory(taskId, tenantId));
    }

    @PutMapping("/{taskId}/transition")
    @Operation(
        summary = "Transition task state",
        description = "Requests a validated state transition for a task scoped to the authenticated tenant. " +
                      "External callers cannot force internal Orchestrator lifecycle states (e.g. UNDERSTANDING, " +
                      "IMPLEMENTING, COMPLETED) directly; attempts will be rejected with 403 Forbidden. " +
                      "Optional tenantId parameter is an assertion: mismatch returns 403.")
    public ApiResponse<TaskResponse> transitionTask(
            @PathVariable String taskId,
            @Parameter(description = "Optional tenant assertion — must match the authenticated tenant if supplied")
            @RequestParam(required = false) @Size(max = 50) String tenantId,
            @Valid @RequestBody TaskTransitionRequest request) {
        return ApiResponse.ok(taskService.transitionTask(taskId, tenantId, request));
    }

    @PostMapping("/{taskId}/cancel")
    @Operation(
        summary = "Cancel task",
        description = "Cancels a non-terminal task for the authenticated tenant. " +
                      "Optional tenantId parameter is an assertion: mismatch returns 403.")
    public ApiResponse<TaskResponse> cancelTask(
            @PathVariable String taskId,
            @Parameter(description = "Optional tenant assertion — must match the authenticated tenant if supplied")
            @RequestParam(required = false) @Size(max = 50) String tenantId,
            @RequestBody(required = false) TaskCancelRequest request) {
        String reason = request != null ? request.reason() : null;
        return ApiResponse.ok(taskService.cancelTask(taskId, tenantId, reason));
    }
}
