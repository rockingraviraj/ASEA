package com.asea.task;

import com.asea.audit.AuditEventType;
import com.asea.audit.AuditService;
import com.asea.budget.BudgetManager;
import com.asea.common.exceptions.EntityNotFoundException;
import com.asea.common.exceptions.InvalidTransitionException;
import com.asea.common.exceptions.PermissionDeniedException;
import com.asea.security.TenantResolver;
import com.asea.state.StateTransition;
import com.asea.state.StateTransitionRepository;
import com.asea.state.TaskState;
import com.asea.task.dto.TaskCreateRequest;
import com.asea.task.dto.TaskHistoryResponse;
import com.asea.task.dto.TaskResponse;
import com.asea.task.dto.TaskTransitionRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("TaskService Tenant Isolation Tests")
class TaskServiceTest {

    private static final String AUTH_TENANT = "tenant-alpha";
    private static final String OTHER_TENANT = "tenant-beta";

    @Mock
    private TaskRepository taskRepository;

    @Mock
    private StateTransitionRepository stateTransitionRepository;

    @Mock
    private AuditService auditService;

    @Mock
    private BudgetManager budgetManager;

    @Mock
    private TenantResolver tenantResolver;

    @Mock
    private com.asea.state.StateManager stateManager;

    @InjectMocks
    private TaskService taskService;

    @Captor
    private ArgumentCaptor<Task> taskCaptor;

    @Captor
    private ArgumentCaptor<StateTransition> transitionCaptor;

    @BeforeEach
    void setUp() {
        // Default behavior for tenantResolver.validateAndResolveTenant
        // When matching or null asserted tenant, return AUTH_TENANT
        // When mismatching asserted tenant, throw PermissionDeniedException
    }

    @Nested
    @DisplayName("Task Creation")
    class CreateTaskTests {

        @Test
        @DisplayName("createTask persists tenant derived from TenantResolver, not any DTO field")
        void createTask_PersistsTenantFromTenantResolver() {
            when(tenantResolver.validateAndResolveTenant(null)).thenReturn(AUTH_TENANT);
            when(taskRepository.save(any(Task.class))).thenAnswer(inv -> {
                Task t = inv.getArgument(0);
                t.setId(1L);
                return t;
            });

            TaskCreateRequest request = new TaskCreateRequest(
                    100L, "https://github.com/org/repo/issues/1", "Task Title",
                    "Task Description", "API", "FEATURE", 1, 10000L,
                    new BigDecimal("10.00"), 3, 60000L, "{\"key\":\"val\"}", null);

            TaskResponse response = taskService.createTask(request);

            verify(tenantResolver).validateAndResolveTenant(null);
            verify(taskRepository).save(taskCaptor.capture());
            Task persistedTask = taskCaptor.getValue();

            assertThat(persistedTask.getTenantId()).isEqualTo(AUTH_TENANT);
            assertThat(response.tenantId()).isEqualTo(AUTH_TENANT);
        }

        @Test
        @DisplayName("createTask initializes budget atomically with persisted tenant and taskId")
        void createTask_InitializesBudgetWithPersistedTenantAndTaskId() {
            when(tenantResolver.validateAndResolveTenant(null)).thenReturn(AUTH_TENANT);
            when(taskRepository.save(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));

            TaskCreateRequest request = new TaskCreateRequest(
                    100L, "https://github.com/org/repo/issues/1", "Task Title",
                    "Description", "API", "FEATURE", 1, 50000L,
                    new BigDecimal("25.50"), 5, 120000L, null);

            taskService.createTask(request);

            verify(budgetManager).initializeBudget(
                    eq(AUTH_TENANT),
                    anyString(),
                    eq(50000L),
                    eq(new BigDecimal("25.50")),
                    eq(5),
                    eq(120000L));
        }

        @Test
        @DisplayName("createTask with matching legacy body assertion succeeds using authenticated tenant")
        void createTask_WithMatchingLegacyAssertion_Succeeds() {
            when(tenantResolver.validateAndResolveTenant(AUTH_TENANT)).thenReturn(AUTH_TENANT);
            when(taskRepository.save(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));

            TaskCreateRequest request = new TaskCreateRequest(
                    100L, null, "Title", "Desc", "MANUAL", "BUG", 0,
                    1000L, new BigDecimal("1.00"), 2, 5000L, null, AUTH_TENANT);

            TaskResponse response = taskService.createTask(request);

            assertThat(response.tenantId()).isEqualTo(AUTH_TENANT);
            verify(tenantResolver).validateAndResolveTenant(AUTH_TENANT);
        }

        @Test
        @DisplayName("createTask with mismatching legacy body assertion throws PermissionDeniedException before save")
        void createTask_WithMismatchingLegacyAssertion_ThrowsForbidden() {
            when(tenantResolver.validateAndResolveTenant(OTHER_TENANT))
                    .thenThrow(new PermissionDeniedException("Cross-tenant access denied"));

            TaskCreateRequest request = new TaskCreateRequest(
                    100L, null, "Title", "Desc", "MANUAL", "BUG", 0,
                    1000L, new BigDecimal("1.00"), 2, 5000L, null, OTHER_TENANT);

            assertThatThrownBy(() -> taskService.createTask(request))
                    .isInstanceOf(PermissionDeniedException.class)
                    .hasMessageContaining("Cross-tenant access denied");

            verify(taskRepository, never()).save(any());
            verifyNoInteractions(budgetManager);
        }
    }

    @Nested
    @DisplayName("Task Retrieval")
    class GetTaskTests {

        @Test
        @DisplayName("getTask uses findByTaskIdAndTenantId with the authenticated tenant")
        void getTask_UsesTenantScopedQuery() {
            when(tenantResolver.validateAndResolveTenant(null)).thenReturn(AUTH_TENANT);
            Task mockTask = createMockTask("ASEA-1234", AUTH_TENANT);
            when(taskRepository.findByTaskIdAndTenantId("ASEA-1234", AUTH_TENANT))
                    .thenReturn(Optional.of(mockTask));

            TaskResponse response = taskService.getTask("ASEA-1234", null);

            assertThat(response.taskId()).isEqualTo("ASEA-1234");
            assertThat(response.tenantId()).isEqualTo(AUTH_TENANT);
            verify(taskRepository).findByTaskIdAndTenantId("ASEA-1234", AUTH_TENANT);
        }

        @Test
        @DisplayName("getTask with matching tenant assertion uses authenticated tenant")
        void getTask_WithMatchingAssertion_UsesAuthenticatedTenant() {
            when(tenantResolver.validateAndResolveTenant(AUTH_TENANT)).thenReturn(AUTH_TENANT);
            Task mockTask = createMockTask("ASEA-1234", AUTH_TENANT);
            when(taskRepository.findByTaskIdAndTenantId("ASEA-1234", AUTH_TENANT))
                    .thenReturn(Optional.of(mockTask));

            TaskResponse response = taskService.getTask("ASEA-1234", AUTH_TENANT);

            assertThat(response.taskId()).isEqualTo("ASEA-1234");
            verify(taskRepository).findByTaskIdAndTenantId("ASEA-1234", AUTH_TENANT);
        }

        @Test
        @DisplayName("getTask with mismatching tenant assertion throws before querying repository")
        void getTask_WithMismatchingAssertion_ThrowsForbidden() {
            when(tenantResolver.validateAndResolveTenant(OTHER_TENANT))
                    .thenThrow(new PermissionDeniedException("Cross-tenant access denied"));

            assertThatThrownBy(() -> taskService.getTask("ASEA-1234", OTHER_TENANT))
                    .isInstanceOf(PermissionDeniedException.class);

            verify(taskRepository, never()).findByTaskIdAndTenantId(anyString(), anyString());
        }

        @Test
        @DisplayName("getTask for task owned by another tenant returns non-disclosing 404")
        void getTask_CrossTenantTask_ReturnsNotFound() {
            when(tenantResolver.validateAndResolveTenant(null)).thenReturn(AUTH_TENANT);
            // Repository returns empty because task belongs to OTHER_TENANT
            when(taskRepository.findByTaskIdAndTenantId("ASEA-CROSS", AUTH_TENANT))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> taskService.getTask("ASEA-CROSS", null))
                    .isInstanceOf(EntityNotFoundException.class)
                    .hasMessage("Task not found: ASEA-CROSS");
        }
    }

    @Nested
    @DisplayName("Task Listing")
    class ListTasksTests {

        @Test
        @DisplayName("listTasks uses resolved tenant for unfiltered queries")
        void listTasks_Unfiltered_UsesResolvedTenant() {
            when(tenantResolver.validateAndResolveTenant(null)).thenReturn(AUTH_TENANT);
            Pageable pageable = PageRequest.of(0, 10);
            Task task1 = createMockTask("ASEA-1", AUTH_TENANT);
            when(taskRepository.findByTenantId(AUTH_TENANT, pageable))
                    .thenReturn(new PageImpl<>(List.of(task1)));

            Page<TaskResponse> page = taskService.listTasks(null, null, pageable);

            assertThat(page.getContent()).hasSize(1);
            assertThat(page.getContent().get(0).tenantId()).isEqualTo(AUTH_TENANT);
            verify(taskRepository).findByTenantId(AUTH_TENANT, pageable);
        }

        @Test
        @DisplayName("listTasks uses resolved tenant for filtered queries")
        void listTasks_Filtered_UsesResolvedTenant() {
            when(tenantResolver.validateAndResolveTenant(null)).thenReturn(AUTH_TENANT);
            Pageable pageable = PageRequest.of(0, 10);
            Task task1 = createMockTask("ASEA-1", AUTH_TENANT);
            when(taskRepository.findByTenantIdAndStatus(AUTH_TENANT, TaskState.CREATED, pageable))
                    .thenReturn(new PageImpl<>(List.of(task1)));

            Page<TaskResponse> page = taskService.listTasks(null, TaskState.CREATED, pageable);

            assertThat(page.getContent()).hasSize(1);
            verify(taskRepository).findByTenantIdAndStatus(AUTH_TENANT, TaskState.CREATED, pageable);
        }

        @Test
        @DisplayName("listTasks with mismatching assertion throws before querying repository")
        void listTasks_MismatchingAssertion_ThrowsForbidden() {
            when(tenantResolver.validateAndResolveTenant(OTHER_TENANT))
                    .thenThrow(new PermissionDeniedException("Cross-tenant access denied"));

            Pageable pageable = PageRequest.of(0, 10);
            assertThatThrownBy(() -> taskService.listTasks(OTHER_TENANT, null, pageable))
                    .isInstanceOf(PermissionDeniedException.class);

            verify(taskRepository, never()).findByTenantId(anyString(), any());
            verify(taskRepository, never()).findByTenantIdAndStatus(anyString(), any(), any());
        }
    }

    @Nested
    @DisplayName("Task History")
    class GetTaskHistoryTests {

        @Test
        @DisplayName("getTaskHistory verifies tenant ownership then executes tenant-scoped query")
        void getTaskHistory_VerifiesOwnershipThenQueriesHistory() {
            when(tenantResolver.validateAndResolveTenant(null)).thenReturn(AUTH_TENANT);
            Task mockTask = createMockTask("ASEA-1234", AUTH_TENANT);
            when(taskRepository.findByTaskIdAndTenantId("ASEA-1234", AUTH_TENANT))
                    .thenReturn(Optional.of(mockTask));

            StateTransition st = new StateTransition();
            st.setId(10L);
            st.setTaskId("ASEA-1234");
            st.setTenantId(AUTH_TENANT);
            st.setFromState(null);
            st.setToState(TaskState.CREATED);
            st.setTrigger("API");
            st.setReason("Task created");
            st.setCreatedAt(LocalDateTime.now());

            when(stateTransitionRepository.findByTaskIdAndTenantIdOrderByCreatedAtAsc("ASEA-1234", AUTH_TENANT))
                    .thenReturn(List.of(st));

            List<TaskHistoryResponse> history = taskService.getTaskHistory("ASEA-1234", null);

            assertThat(history).hasSize(1);
            assertThat(history.get(0).taskId()).isEqualTo("ASEA-1234");
            verify(taskRepository).findByTaskIdAndTenantId("ASEA-1234", AUTH_TENANT);
            verify(stateTransitionRepository).findByTaskIdAndTenantIdOrderByCreatedAtAsc("ASEA-1234", AUTH_TENANT);
        }

        @Test
        @DisplayName("getTaskHistory for cross-tenant task returns 404 without querying history")
        void getTaskHistory_CrossTenantTask_ReturnsNotFound() {
            when(tenantResolver.validateAndResolveTenant(null)).thenReturn(AUTH_TENANT);
            when(taskRepository.findByTaskIdAndTenantId("ASEA-CROSS", AUTH_TENANT))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> taskService.getTaskHistory("ASEA-CROSS", null))
                    .isInstanceOf(EntityNotFoundException.class);

            verify(stateTransitionRepository, never())
                    .findByTaskIdAndTenantIdOrderByCreatedAtAsc(anyString(), anyString());
        }
    }

    @Nested
    @DisplayName("Task State Transition & Cancellation")
    class TransitionTaskTests {

        @Test
        @DisplayName("transitionTask resolves tenant and delegates to StateManager")
        void transitionTask_EnforcesTenantScopedStateTransition() {
            when(tenantResolver.validateAndResolveTenant(null)).thenReturn(AUTH_TENANT);
            Task mockTask = createMockTask("ASEA-1234", AUTH_TENANT);
            mockTask.setCurrentState(TaskState.FAILED);
            mockTask.setStatus(TaskState.FAILED);

            when(stateManager.transition(AUTH_TENANT, "ASEA-1234", TaskState.FAILED, "TRIGGER", "reason", false))
                    .thenReturn(mockTask);

            TaskTransitionRequest req = new TaskTransitionRequest(TaskState.FAILED, "TRIGGER", "reason");
            TaskResponse resp = taskService.transitionTask("ASEA-1234", null, req);

            assertThat(resp.currentState()).isEqualTo(TaskState.FAILED);
            verify(stateManager).transition(AUTH_TENANT, "ASEA-1234", TaskState.FAILED, "TRIGGER", "reason", false);
        }

        @Test
        @DisplayName("cancelTask resolves tenant and calls stateManager.cancelTask")
        void cancelTask_DelegatesToStateManager() {
            when(tenantResolver.validateAndResolveTenant("tenant-alpha")).thenReturn(AUTH_TENANT);
            Task mockTask = createMockTask("ASEA-1234", AUTH_TENANT);
            mockTask.setCurrentState(TaskState.FAILED);
            mockTask.setStatus(TaskState.FAILED);

            when(stateManager.cancelTask(AUTH_TENANT, "ASEA-1234", "User requested cancel"))
                    .thenReturn(mockTask);

            TaskResponse resp = taskService.cancelTask("ASEA-1234", "tenant-alpha", "User requested cancel");

            assertThat(resp.currentState()).isEqualTo(TaskState.FAILED);
            verify(stateManager).cancelTask(AUTH_TENANT, "ASEA-1234", "User requested cancel");
        }

        @Test
        @DisplayName("transitionInternal invokes stateManager with internal authority flag")
        void transitionInternal_InvokesWithInternalAuthority() {
            Task mockTask = createMockTask("ASEA-1234", AUTH_TENANT);
            mockTask.setCurrentState(TaskState.UNDERSTANDING);
            mockTask.setStatus(TaskState.UNDERSTANDING);

            when(stateManager.transition(AUTH_TENANT, "ASEA-1234", TaskState.UNDERSTANDING, "SYSTEM_TRIGGER", "Automated step", true))
                    .thenReturn(mockTask);

            TaskResponse resp = taskService.transitionInternal("ASEA-1234", AUTH_TENANT, TaskState.UNDERSTANDING, "SYSTEM_TRIGGER", "Automated step");

            assertThat(resp.currentState()).isEqualTo(TaskState.UNDERSTANDING);
            verify(stateManager).transition(AUTH_TENANT, "ASEA-1234", TaskState.UNDERSTANDING, "SYSTEM_TRIGGER", "Automated step", true);
        }
    }

    private Task createMockTask(String taskId, String tenantId) {
        Task t = new Task();
        t.setId(1L);
        t.setTaskId(taskId);
        t.setTenantId(tenantId);
        t.setTitle("Test Task");
        t.setTaskType("FEATURE");
        t.setStatus(TaskState.CREATED);
        t.setCurrentState(TaskState.CREATED);
        t.setCreatedAt(LocalDateTime.now());
        t.setUpdatedAt(LocalDateTime.now());
        return t;
    }
}
