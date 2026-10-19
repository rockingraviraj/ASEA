package com.asea.repository;

import com.asea.approval.Approval;
import com.asea.approval.ApprovalRepository;
import com.asea.audit.AuditEventType;
import com.asea.audit.AuditLog;
import com.asea.audit.AuditLogRepository;
import com.asea.budget.Budget;
import com.asea.budget.BudgetRepository;
import com.asea.retry.RetryAttempt;
import com.asea.retry.RetryAttemptRepository;
import com.asea.state.StateTransition;
import com.asea.state.StateTransitionRepository;
import com.asea.state.TaskState;
import com.asea.task.Task;
import com.asea.task.TaskRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PostgreSQL Testcontainers integration test verifying schema migrations and tenant-scoped repository queries.
 *
 * <p>Uses {@code disabledWithoutDocker = true} so that when Docker is not available in the execution
 * environment, the suite skips these tests without breaking local builds, marking them BLOCKED / NOT VERIFIED.</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("PostgreSQL Testcontainers Tenant Isolation Integration Tests")
class PostgresTenantIsolationIntegrationTest {

    private static final String TENANT_A = "tenant-a";
    private static final String TENANT_B = "tenant-b";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("aseadb")
            .withUsername("asea")
            .withPassword("asea_secret");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    }

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private StateTransitionRepository stateTransitionRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private BudgetRepository budgetRepository;

    @Autowired
    private RetryAttemptRepository retryAttemptRepository;

    @Autowired
    private ApprovalRepository approvalRepository;

    @Test
    @DisplayName("Task queries strictly isolate rows by tenantId")
    void taskRepository_StrictIsolation() {
        Task taskA = new Task();
        taskA.setTaskId("ASEA-TASK-A");
        taskA.setTenantId(TENANT_A);
        taskA.setTitle("Task for Tenant A");
        taskA.setTaskType("FEATURE");
        taskA.setStatus(TaskState.CREATED);
        taskA.setCurrentState(TaskState.CREATED);
        taskRepository.save(taskA);

        Optional<Task> foundByTenantA = taskRepository.findByTaskIdAndTenantId("ASEA-TASK-A", TENANT_A);
        assertThat(foundByTenantA).isPresent();

        Optional<Task> foundByTenantB = taskRepository.findByTaskIdAndTenantId("ASEA-TASK-A", TENANT_B);
        assertThat(foundByTenantB).isEmpty();

        Page<Task> listB = taskRepository.findByTenantId(TENANT_B, PageRequest.of(0, 10));
        assertThat(listB.getContent()).noneMatch(t -> t.getTaskId().equals("ASEA-TASK-A"));
    }

    @Test
    @DisplayName("State transition queries strictly isolate history by tenantId")
    void stateTransitionRepository_StrictIsolation() {
        StateTransition stA = new StateTransition();
        stA.setTaskId("ASEA-TASK-A");
        stA.setTenantId(TENANT_A);
        stA.setFromState(TaskState.CREATED);
        stA.setToState(TaskState.UNDERSTANDING);
        stA.setTrigger("TEST");
        stA.setCreatedAt(LocalDateTime.now());
        stateTransitionRepository.save(stA);

        List<StateTransition> historyA = stateTransitionRepository
                .findByTaskIdAndTenantIdOrderByCreatedAtAsc("ASEA-TASK-A", TENANT_A);
        assertThat(historyA).hasSize(1);

        List<StateTransition> historyB = stateTransitionRepository
                .findByTaskIdAndTenantIdOrderByCreatedAtAsc("ASEA-TASK-A", TENANT_B);
        assertThat(historyB).isEmpty();
    }

    @Test
    @DisplayName("Audit queries strictly isolate logs by tenantId")
    void auditLogRepository_StrictIsolation() {
        AuditLog auditA = new AuditLog();
        auditA.setTaskId("ASEA-TASK-A");
        auditA.setTenantId(TENANT_A);
        auditA.setEventType(AuditEventType.TASK_CREATED);
        auditA.setActor("SYSTEM");
        auditA.setCreatedAt(LocalDateTime.now());
        auditLogRepository.save(auditA);

        List<AuditLog> logsA = auditLogRepository
                .findByTenantIdAndTaskIdOrderByCreatedAtAsc(TENANT_A, "ASEA-TASK-A");
        assertThat(logsA).hasSize(1);

        List<AuditLog> logsB = auditLogRepository
                .findByTenantIdAndTaskIdOrderByCreatedAtAsc(TENANT_B, "ASEA-TASK-A");
        assertThat(logsB).isEmpty();
    }

    @Test
    @DisplayName("Budget queries strictly isolate budget and locks by tenantId")
    void budgetRepository_StrictIsolation() {
        Budget budgetA = new Budget();
        budgetA.setTaskId("ASEA-TASK-A");
        budgetA.setTenantId(TENANT_A);
        budgetA.setMaxTokens(50000L);
        budgetA.setMaxCost(new BigDecimal("10.00"));
        budgetA.setUsedTokens(0L);
        budgetA.setUsedCost(BigDecimal.ZERO);
        budgetA.setStatus("ACTIVE");
        budgetA.setCreatedAt(LocalDateTime.now());
        budgetA.setUpdatedAt(LocalDateTime.now());
        budgetRepository.save(budgetA);

        Optional<Budget> readA = budgetRepository.findByTenantIdAndTaskId(TENANT_A, "ASEA-TASK-A");
        assertThat(readA).isPresent();

        Optional<Budget> readB = budgetRepository.findByTenantIdAndTaskId(TENANT_B, "ASEA-TASK-A");
        assertThat(readB).isEmpty();
    }

    @Test
    @DisplayName("Retry attempt queries strictly isolate records by tenantId")
    void retryAttemptRepository_StrictIsolation() {
        RetryAttempt retryA = new RetryAttempt();
        retryA.setTaskId("ASEA-TASK-A");
        retryA.setTenantId(TENANT_A);
        retryA.setAttemptNumber(1);
        retryA.setStatus("FAILED");
        retryA.setCreatedAt(LocalDateTime.now());
        retryAttemptRepository.save(retryA);

        List<RetryAttempt> listA = retryAttemptRepository
                .findByTenantIdAndTaskIdOrderByAttemptNumberAsc(TENANT_A, "ASEA-TASK-A");
        assertThat(listA).hasSize(1);

        List<RetryAttempt> listB = retryAttemptRepository
                .findByTenantIdAndTaskIdOrderByAttemptNumberAsc(TENANT_B, "ASEA-TASK-A");
        assertThat(listB).isEmpty();
    }

    @Test
    @DisplayName("Approval queries strictly isolate approvals by tenantId")
    void approvalRepository_StrictIsolation() {
        Approval appA = new Approval();
        appA.setApprovalId("APP-001");
        appA.setTaskId("ASEA-TASK-A");
        appA.setTenantId(TENANT_A);
        appA.setStatus("PENDING");
        appA.setEscalationType("BUDGET");
        appA.setRequestedAt(LocalDateTime.now());
        approvalRepository.save(appA);

        Optional<Approval> foundA = approvalRepository.findByTenantIdAndApprovalId(TENANT_A, "APP-001");
        assertThat(foundA).isPresent();

        Optional<Approval> foundB = approvalRepository.findByTenantIdAndApprovalId(TENANT_B, "APP-001");
        assertThat(foundB).isEmpty();
    }
}
