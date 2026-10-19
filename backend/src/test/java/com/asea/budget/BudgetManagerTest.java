package com.asea.budget;

import com.asea.audit.AuditService;
import com.asea.common.exceptions.BudgetExceededException;
import com.asea.common.exceptions.ValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("BudgetManager Safety and Limit Enforcement Tests")
class BudgetManagerTest {

    private static final String TENANT_ID = "tenant-alpha";
    private static final String TASK_ID = "ASEA-1234";

    @Mock
    private BudgetRepository budgetRepository;

    @Mock
    private AuditService auditService;

    private BudgetProperties budgetProperties;
    private BudgetManager budgetManager;

    @BeforeEach
    void setUp() {
        budgetProperties = new BudgetProperties();
        budgetProperties.setDefaultBudgetTokens(100_000);
        budgetProperties.setDefaultBudgetCost(BigDecimal.valueOf(10.00));
        budgetProperties.setDefaultMaxAttempts(3);
        budgetProperties.setDefaultMaxExecutionMs(300_000L); // 5 min
        budgetProperties.setHardMaxTokens(1_000_000);
        budgetProperties.setHardMaxCost(BigDecimal.valueOf(100.00));
        budgetProperties.setHardMaxAttempts(10);
        budgetProperties.setHardMaxExecutionMs(3_600_000L); // 1 hr

        budgetManager = new BudgetManager(budgetRepository, auditService, budgetProperties);
    }

    @Test
    @DisplayName("initializeBudget uses finite defaults when request values are null")
    void initializeBudget_AppliesFiniteDefaults() {
        when(budgetRepository.save(any(Budget.class))).thenAnswer(inv -> inv.getArgument(0));

        Budget budget = budgetManager.initializeBudget(TENANT_ID, TASK_ID, null, null, null, null);

        assertThat(budget.getBudgetTokens()).isEqualTo(100_000);
        assertThat(budget.getBudgetCost()).isEqualByComparingTo(BigDecimal.valueOf(10.00));
        assertThat(budget.getMaxAttempts()).isEqualTo(3);
        assertThat(budget.getMaxExecutionMs()).isEqualTo(300_000L);
        assertThat(budget.getStatus()).isEqualTo(BudgetStatus.ACTIVE);
    }

    @Test
    @DisplayName("initializeBudget rejects non-positive or excessive limits")
    void initializeBudget_RejectsInvalidLimits() {
        assertThatThrownBy(() -> budgetManager.initializeBudget(TENANT_ID, TASK_ID, -5, null, null, null))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("must be positive");

        assertThatThrownBy(() -> budgetManager.initializeBudget(TENANT_ID, TASK_ID, 5_000_000, null, null, null))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("exceeds system maximum");
    }

    @Test
    @DisplayName("check() fails closed when budget record is missing")
    void check_MissingBudget_FailsClosed() {
        when(budgetRepository.findByTenantIdAndTaskId(TENANT_ID, TASK_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> budgetManager.check(TENANT_ID, TASK_ID))
                .isInstanceOf(BudgetExceededException.class)
                .hasMessageContaining("No budget record found");
    }

    @Test
    @DisplayName("check() fails when budget status is not ACTIVE")
    void check_InactiveStatus_Fails() {
        Budget b = createTestBudget();
        b.setStatus(BudgetStatus.EXHAUSTED);
        when(budgetRepository.findByTenantIdAndTaskId(TENANT_ID, TASK_ID)).thenReturn(Optional.of(b));

        BudgetCheckResult result = budgetManager.check(TENANT_ID, TASK_ID);
        assertThat(result.allowed()).isFalse();
        assertThat(result.reason()).contains("is not active");
    }

    @Test
    @DisplayName("check() enforces elapsed execution time limit")
    void check_ExecutionTimeExhausted_Fails() {
        Budget b = createTestBudget();
        b.setMaxExecutionMs(1000L); // 1 sec limit
        b.setCreatedAt(LocalDateTime.now().minusSeconds(5)); // created 5s ago
        when(budgetRepository.findByTenantIdAndTaskId(TENANT_ID, TASK_ID)).thenReturn(Optional.of(b));

        BudgetCheckResult result = budgetManager.check(TENANT_ID, TASK_ID);
        assertThat(result.allowed()).isFalse();
        assertThat(result.reason()).contains("Execution time limit exceeded");
    }

    @Test
    @DisplayName("incrementAttempts atomically blocks increment when limit is reached")
    void incrementAttempts_ExceededLimit_FailsClosed() {
        Budget b = createTestBudget();
        b.setMaxAttempts(2);
        b.setCurrentAttempts(2); // already at limit
        when(budgetRepository.findByTenantIdAndTaskId(TENANT_ID, TASK_ID)).thenReturn(Optional.of(b));

        assertThatThrownBy(() -> budgetManager.incrementAttempts(TENANT_ID, TASK_ID))
                .isInstanceOf(BudgetExceededException.class)
                .hasMessageContaining("Maximum attempts reached");
    }

    @Test
    @DisplayName("recordUsage rejects negative tokens or cost")
    void recordUsage_NegativeValues_ThrowsValidationException() {
        assertThatThrownBy(() -> budgetManager.recordUsage(TENANT_ID, TASK_ID, -10, BigDecimal.ZERO))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> budgetManager.recordUsage(TENANT_ID, TASK_ID, 10, BigDecimal.valueOf(-1.00)))
                .isInstanceOf(ValidationException.class);
    }

    private Budget createTestBudget() {
        Budget b = new Budget();
        b.setId(1L);
        b.setTenantId(TENANT_ID);
        b.setTaskId(TASK_ID);
        b.setBudgetTokens(100_000);
        b.setBudgetCost(BigDecimal.valueOf(10.00));
        b.setUsedTokens(1000);
        b.setUsedCost(BigDecimal.valueOf(0.10));
        b.setMaxAttempts(3);
        b.setCurrentAttempts(1);
        b.setMaxExecutionMs(300_000L);
        b.setStatus(BudgetStatus.ACTIVE);
        b.setCreatedAt(LocalDateTime.now());
        b.setUpdatedAt(LocalDateTime.now());
        return b;
    }
}
