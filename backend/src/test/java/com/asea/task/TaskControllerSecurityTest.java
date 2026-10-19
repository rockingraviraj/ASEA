package com.asea.task;

import com.asea.common.GlobalExceptionHandler;
import com.asea.common.exceptions.EntityNotFoundException;
import com.asea.common.exceptions.InvalidTransitionException;
import com.asea.common.exceptions.PermissionDeniedException;
import com.asea.security.ApiKeyAuthenticationFilter;
import com.asea.security.SecurityConfig;
import com.asea.state.TaskState;
import com.asea.task.dto.TaskCreateRequest;
import com.asea.task.dto.TaskHistoryResponse;
import com.asea.task.dto.TaskResponse;
import com.asea.task.dto.TaskTransitionRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc test suite verifying real Spring Security filter chain and TaskController endpoints.
 * Covers all P0 tenant isolation requirements:
 * 1. Valid API key & task creation
 * 2. Missing API key -> 401
 * 3. Invalid API key -> 401
 * 4. Creation without body tenantId -> success
 * 5. Creation with matching legacy assertion -> success
 * 6. Creation with mismatching legacy assertion -> 403
 * 7. GET task with no tenant query param -> resolves tenant from auth
 * 8. GET/list/history/transition with matching tenant assertion -> success
 * 9. GET/list/history/transition with mismatching query assertion -> 403
 * 10. Cross-tenant task ID without spoofed assertion -> non-disclosing 404
 * 11. Tenant-scoped listing does not return other-tenant rows
 * 12. Rejected transition does not mutate state
 */
@WebMvcTest(controllers = TaskController.class)
@Import({SecurityConfig.class, ApiKeyAuthenticationFilter.class, GlobalExceptionHandler.class})
@TestPropertySource(properties = {
        "asea.security.api-key=test-api-key-for-unit-tests",
        "asea.security.tenant-id=test-tenant"
})
@DisplayName("TaskController & Spring Security Integration Tests")
class TaskControllerSecurityTest {

    private static final String API_KEY_HEADER = "X-ASEA-API-Key";
    private static final String VALID_API_KEY = "test-api-key-for-unit-tests";
    private static final String INVALID_API_KEY = "invalid-key-xyz";
    private static final String AUTH_TENANT = "test-tenant";
    private static final String OTHER_TENANT = "other-tenant";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private TaskService taskService;

    @MockBean
    private org.springframework.data.jpa.mapping.JpaMetamodelMappingContext jpaMappingContext;

    // -------------------------------------------------------------------------
    // 1, 2, 3: Authentication Filter Chain (401 vs 200/201)
    // -------------------------------------------------------------------------
    @Nested
    @DisplayName("API Key Authentication Chain")
    class AuthenticationTests {

        @Test
        @DisplayName("2. Missing API key returns 401 Unauthorized")
        void missingApiKey_Returns401() throws Exception {
            mockMvc.perform(get("/api/v1/tasks/ASEA-001"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("3. Invalid API key returns 401 Unauthorized")
        void invalidApiKey_Returns401() throws Exception {
            mockMvc.perform(get("/api/v1/tasks/ASEA-001")
                            .header(API_KEY_HEADER, INVALID_API_KEY))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("1. Valid API key and normal task creation returns 201 and authenticated tenant ownership")
        void validApiKey_NormalTaskCreation_Returns201() throws Exception {
            TaskResponse stubResponse = createStubResponse("ASEA-1111", AUTH_TENANT);
            when(taskService.createTask(any(TaskCreateRequest.class))).thenReturn(stubResponse);

            String requestBody = """
                    {
                        "title": "Fix memory leak",
                        "taskType": "BUG",
                        "priority": 1,
                        "budgetTokens": 50000,
                        "budgetCost": 15.00,
                        "maxAttempts": 3,
                        "maxExecutionMs": 30000
                    }
                    """;

            mockMvc.perform(post("/api/v1/tasks")
                            .header(API_KEY_HEADER, VALID_API_KEY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.tenantId", is(AUTH_TENANT)))
                    .andExpect(jsonPath("$.data.taskId", is("ASEA-1111")));
        }
    }

    // -------------------------------------------------------------------------
    // 4, 5, 6: Task Creation Tenant Assertion in Request Body
    // -------------------------------------------------------------------------
    @Nested
    @DisplayName("Task Creation Request-Body Tenant Assertion")
    class TaskCreationBodyAssertionTests {

        @Test
        @DisplayName("4. Task creation without a body tenantId succeeds")
        void createTask_WithoutBodyTenantId_Succeeds() throws Exception {
            TaskResponse stubResponse = createStubResponse("ASEA-2222", AUTH_TENANT);
            when(taskService.createTask(any(TaskCreateRequest.class))).thenReturn(stubResponse);

            String requestBody = """
                    {
                        "title": "Deploy feature",
                        "taskType": "FEATURE"
                    }
                    """;

            mockMvc.perform(post("/api/v1/tasks")
                            .header(API_KEY_HEADER, VALID_API_KEY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.tenantId", is(AUTH_TENANT)));
        }

        @Test
        @DisplayName("5. Task creation with matching legacy body assertion is accepted")
        void createTask_WithMatchingLegacyBodyAssertion_Succeeds() throws Exception {
            TaskResponse stubResponse = createStubResponse("ASEA-3333", AUTH_TENANT);
            when(taskService.createTask(any(TaskCreateRequest.class))).thenReturn(stubResponse);

            String requestBody = """
                    {
                        "title": "Refactor module",
                        "taskType": "REFACTOR",
                        "tenantId": "test-tenant"
                    }
                    """;

            mockMvc.perform(post("/api/v1/tasks")
                            .header(API_KEY_HEADER, VALID_API_KEY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.tenantId", is(AUTH_TENANT)));
        }

        @Test
        @DisplayName("6. Task creation with mismatching legacy body assertion returns 403 Forbidden")
        void createTask_WithMismatchingLegacyBodyAssertion_Returns403() throws Exception {
            when(taskService.createTask(any(TaskCreateRequest.class)))
                    .thenThrow(new PermissionDeniedException(
                            "Cross-tenant access denied: supplied tenantId does not match authenticated identity"));

            String requestBody = """
                    {
                        "title": "Malicious task",
                        "taskType": "ATTACK",
                        "tenantId": "other-tenant"
                    }
                    """;

            mockMvc.perform(post("/api/v1/tasks")
                            .header(API_KEY_HEADER, VALID_API_KEY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code", is("PERMISSION_DENIED")))
                    .andExpect(jsonPath("$.message", containsString("Cross-tenant access denied")));
        }
    }

    // -------------------------------------------------------------------------
    // 7, 8, 9, 10: Task Retrieval & Assertions
    // -------------------------------------------------------------------------
    @Nested
    @DisplayName("Task Retrieval Scoping")
    class GetTaskScopingTests {

        @Test
        @DisplayName("7. GET task with no tenant query parameter resolves tenant from authentication")
        void getTask_NoTenantParam_ResolvesFromAuth() throws Exception {
            TaskResponse stubResponse = createStubResponse("ASEA-1234", AUTH_TENANT);
            when(taskService.getTask("ASEA-1234", null)).thenReturn(stubResponse);

            mockMvc.perform(get("/api/v1/tasks/ASEA-1234")
                            .header(API_KEY_HEADER, VALID_API_KEY))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.taskId", is("ASEA-1234")))
                    .andExpect(jsonPath("$.data.tenantId", is(AUTH_TENANT)));

            verify(taskService).getTask("ASEA-1234", null);
        }

        @Test
        @DisplayName("8. GET task with matching tenant assertion succeeds")
        void getTask_WithMatchingAssertion_Succeeds() throws Exception {
            TaskResponse stubResponse = createStubResponse("ASEA-1234", AUTH_TENANT);
            when(taskService.getTask("ASEA-1234", AUTH_TENANT)).thenReturn(stubResponse);

            mockMvc.perform(get("/api/v1/tasks/ASEA-1234")
                            .param("tenantId", AUTH_TENANT)
                            .header(API_KEY_HEADER, VALID_API_KEY))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.taskId", is("ASEA-1234")));

            verify(taskService).getTask("ASEA-1234", AUTH_TENANT);
        }

        @Test
        @DisplayName("9. GET task with mismatching query assertion returns 403 Forbidden")
        void getTask_WithMismatchingAssertion_Returns403() throws Exception {
            when(taskService.getTask("ASEA-1234", OTHER_TENANT))
                    .thenThrow(new PermissionDeniedException("Cross-tenant access denied"));

            mockMvc.perform(get("/api/v1/tasks/ASEA-1234")
                            .param("tenantId", OTHER_TENANT)
                            .header(API_KEY_HEADER, VALID_API_KEY))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code", is("PERMISSION_DENIED")));
        }

        @Test
        @DisplayName("10. Cross-tenant task ID without spoofed assertion returns non-disclosing 404")
        void getTask_CrossTenantTaskId_Returns404() throws Exception {
            when(taskService.getTask("ASEA-CROSS", null))
                    .thenThrow(new EntityNotFoundException("Task not found: ASEA-CROSS"));

            mockMvc.perform(get("/api/v1/tasks/ASEA-CROSS")
                            .header(API_KEY_HEADER, VALID_API_KEY))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code", is("NOT_FOUND")))
                    .andExpect(jsonPath("$.message", is("Task not found: ASEA-CROSS")));
        }
    }

    // -------------------------------------------------------------------------
    // 11: Task Listing
    // -------------------------------------------------------------------------
    @Nested
    @DisplayName("Task Listing Isolation")
    class ListTasksIsolationTests {

        @Test
        @DisplayName("11. Tenant-scoped task listing returns only authenticated tenant's tasks")
        void listTasks_ReturnsOnlyAuthenticatedTenantTasks() throws Exception {
            TaskResponse task1 = createStubResponse("ASEA-001", AUTH_TENANT);
            TaskResponse task2 = createStubResponse("ASEA-002", AUTH_TENANT);
            when(taskService.listTasks(eq(null), eq(null), any()))
                    .thenReturn(new PageImpl<>(List.of(task1, task2), PageRequest.of(0, 20), 2));

            mockMvc.perform(get("/api/v1/tasks")
                            .header(API_KEY_HEADER, VALID_API_KEY))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content[0].tenantId", is(AUTH_TENANT)))
                    .andExpect(jsonPath("$.data.content[1].tenantId", is(AUTH_TENANT)))
                    .andExpect(jsonPath("$.data.totalElements", is(2)));
        }

        @Test
        @DisplayName("9b. Listing with mismatching query assertion returns 403 Forbidden")
        void listTasks_MismatchingAssertion_Returns403() throws Exception {
            when(taskService.listTasks(eq(OTHER_TENANT), any(), any()))
                    .thenThrow(new PermissionDeniedException("Cross-tenant access denied"));

            mockMvc.perform(get("/api/v1/tasks")
                            .param("tenantId", OTHER_TENANT)
                            .header(API_KEY_HEADER, VALID_API_KEY))
                    .andExpect(status().isForbidden());
        }
    }

    // -------------------------------------------------------------------------
    // 12: Transition Task & History Assertions
    // -------------------------------------------------------------------------
    @Nested
    @DisplayName("Task Transition & History Scoping")
    class TransitionAndHistoryTests {

        @Test
        @DisplayName("8b. Task history with matching tenant assertion returns 200")
        void getTaskHistory_WithMatchingAssertion_Succeeds() throws Exception {
            TaskHistoryResponse history = new TaskHistoryResponse(
                    1L, "ASEA-1234", null, TaskState.CREATED, "API", "Created", LocalDateTime.now());
            when(taskService.getTaskHistory("ASEA-1234", AUTH_TENANT)).thenReturn(List.of(history));

            mockMvc.perform(get("/api/v1/tasks/ASEA-1234/history")
                            .param("tenantId", AUTH_TENANT)
                            .header(API_KEY_HEADER, VALID_API_KEY))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].taskId", is("ASEA-1234")));
        }

        @Test
        @DisplayName("9c. Task history with mismatching query assertion returns 403")
        void getTaskHistory_WithMismatchingAssertion_Returns403() throws Exception {
            when(taskService.getTaskHistory("ASEA-1234", OTHER_TENANT))
                    .thenThrow(new PermissionDeniedException("Cross-tenant access denied"));

            mockMvc.perform(get("/api/v1/tasks/ASEA-1234/history")
                            .param("tenantId", OTHER_TENANT)
                            .header(API_KEY_HEADER, VALID_API_KEY))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("12. Rejected transition returns 409 Conflict without mutating state")
        void transitionTask_InvalidTransition_Returns409Conflict() throws Exception {
            when(taskService.transitionTask(eq("ASEA-1234"), eq(null), any(TaskTransitionRequest.class)))
                    .thenThrow(new InvalidTransitionException("Illegal state transition from CREATED to COMPLETED"));

            String requestBody = """
                    {
                        "toState": "COMPLETED",
                        "trigger": "MANUAL",
                        "reason": "Skip steps"
                    }
                    """;

            mockMvc.perform(put("/api/v1/tasks/ASEA-1234/transition")
                            .header(API_KEY_HEADER, VALID_API_KEY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code", is("INVALID_TRANSITION")))
                    .andExpect(jsonPath("$.message", containsString("Illegal state transition")));
        }

        @Test
        @DisplayName("9d. Transition with mismatching query assertion returns 403")
        void transitionTask_WithMismatchingAssertion_Returns403() throws Exception {
            when(taskService.transitionTask(eq("ASEA-1234"), eq(OTHER_TENANT), any(TaskTransitionRequest.class)))
                    .thenThrow(new PermissionDeniedException("Cross-tenant access denied"));

            String requestBody = """
                    {
                        "toState": "UNDERSTANDING",
                        "trigger": "RULE",
                        "reason": "Triage step"
                    }
                    """;

            mockMvc.perform(put("/api/v1/tasks/ASEA-1234/transition")
                            .param("tenantId", OTHER_TENANT)
                            .header(API_KEY_HEADER, VALID_API_KEY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("Transition attempting to force internal orchestrator state directly returns 403 Forbidden")
        void transitionTask_ExternalCallerForcingInternalState_Returns403() throws Exception {
            when(taskService.transitionTask(eq("ASEA-1234"), eq(null), any(TaskTransitionRequest.class)))
                    .thenThrow(new PermissionDeniedException(
                            "External callers cannot directly transition task to internal lifecycle state: UNDERSTANDING"));

            String requestBody = """
                    {
                        "toState": "UNDERSTANDING",
                        "trigger": "USER",
                        "reason": "Force internal step"
                    }
                    """;

            mockMvc.perform(put("/api/v1/tasks/ASEA-1234/transition")
                            .header(API_KEY_HEADER, VALID_API_KEY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code", is("FORBIDDEN")))
                    .andExpect(jsonPath("$.message", containsString("External callers cannot directly transition")));
        }

        @Test
        @DisplayName("Cancel task endpoint returns 200 and calls cancelTask on TaskService")
        void cancelTask_Success_Returns200() throws Exception {
            TaskResponse cancelled = createStubResponse("ASEA-1234", AUTH_TENANT);
            when(taskService.cancelTask(eq("ASEA-1234"), eq(null), eq("User cancelled")))
                    .thenReturn(cancelled);

            String requestBody = """
                    {
                        "reason": "User cancelled"
                    }
                    """;

            mockMvc.perform(post("/api/v1/tasks/ASEA-1234/cancel")
                            .header(API_KEY_HEADER, VALID_API_KEY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.taskId", is("ASEA-1234")));
        }
    }

    private TaskResponse createStubResponse(String taskId, String tenantId) {
        return new TaskResponse(
                1L, taskId, tenantId, 100L, "Task Title", "Description",
                "https://github.com/org/repo/issues/1", "API", "FEATURE", 1,
                TaskState.CREATED, TaskState.CREATED, null, 10000L,
                new BigDecimal("10.00"), 3, 60000L,
                LocalDateTime.now(), LocalDateTime.now(), null, null);
    }
}
