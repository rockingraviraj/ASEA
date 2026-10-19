package com.asea.budget;

import com.asea.audit.AuditEventType;
import com.asea.audit.AuditService;
import com.asea.common.exceptions.BudgetExceededException;
import com.asea.common.exceptions.ValidationException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Budget Manager — enforces spending limits for tasks.
 *
 * <p>Before any expensive operation (LLM call, sandbox execution), the orchestrator
 * checks the budget. When a limit is exceeded, the task is escalated rather than
 * allowed to continue indefinitely.</p>
 *
 * <p>Uses pessimistic locking to prevent race conditions when multiple concurrent
 * operations consume budget simultaneously.</p>
 *
 * <p><b>Finite Limits Guarantee:</b> Tasks must have finite limits. Null values on
 * creation are automatically assigned server-configured finite defaults from {@link BudgetProperties}.</p>
 *
 * <p><b>Fail-closed contract:</b> if no budget record is found, or if budget is inactive/exhausted/expired,
 * execution is DENIED.</p>
 *
 * <p><b>Tenant isolation:</b> all budget operations use tenant-scoped queries (tenantId + taskId).</p>
 */
@Service
public class BudgetManager {

    private static final Logger log = LoggerFactory.getLogger(BudgetManager.class);

    private final BudgetRepository budgetRepository;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final BudgetProperties budgetProperties;

    public BudgetManager(BudgetRepository budgetRepository,
                         AuditService auditService,
                         ObjectMapper objectMapper,
                         BudgetProperties budgetProperties) {
        this.budgetRepository = budgetRepository;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.budgetProperties = budgetProperties != null ? budgetProperties : new BudgetProperties();
    }

    /**
     * Initialises a budget record with validated, finite limits.
     * Missing limits receive finite server-configured defaults.
     */
    @Transactional
    public Budget initializeBudget(String tenantId, String taskId,
                                   Long maxTokens, BigDecimal maxCost,
                                   Integer maxAttempts, Long maxExecutionMs) {
        // Enforce finite, non-null values with hard bounds
        long tokens = (maxTokens != null) ? maxTokens : budgetProperties.getDefaultMaxTokens();
        if (tokens <= 0 || tokens > BudgetProperties.HARD_MAX_TOKENS) {
            throw new ValidationException("maxTokens must be between 1 and " + BudgetProperties.HARD_MAX_TOKENS);
        }

        BigDecimal cost = (maxCost != null) ? maxCost : budgetProperties.getDefaultMaxCost();
        if (cost.compareTo(BigDecimal.ZERO) <= 0 || cost.compareTo(BudgetProperties.HARD_MAX_COST) > 0) {
            throw new ValidationException("maxCost must be between 0.01 and " + BudgetProperties.HARD_MAX_COST);
        }

        int attempts = (maxAttempts != null) ? maxAttempts : budgetProperties.getDefaultMaxAttempts();
        if (attempts <= 0 || attempts > BudgetProperties.HARD_MAX_ATTEMPTS) {
            throw new ValidationException("maxAttempts must be between 1 and " + BudgetProperties.HARD_MAX_ATTEMPTS);
        }

        long executionMs = (maxExecutionMs != null) ? maxExecutionMs : budgetProperties.getDefaultMaxExecutionMs();
        if (executionMs < 1000L || executionMs > BudgetProperties.HARD_MAX_EXECUTION_MS) {
            throw new ValidationException("maxExecutionMs must be between 1000ms and " + BudgetProperties.HARD_MAX_EXECUTION_MS + "ms");
        }

        Budget budget = new Budget();
        budget.setTenantId(tenantId);
        budget.setTaskId(taskId);
        budget.setMaxTokens(tokens);
        budget.setMaxCost(cost);
        budget.setMaxAttempts(attempts);
        budget.setMaxExecutionMs(executionMs);
        budget.setUsedTokens(0L);
        budget.setUsedCost(BigDecimal.ZERO);
        budget.setCurrentAttempts(0);
        budget.setStartedAt(LocalDateTime.now());
        budget.setStatus("ACTIVE");
        budget.setCreatedAt(LocalDateTime.now());
        budget.setUpdatedAt(LocalDateTime.now());

        Budget saved = budgetRepository.save(budget);
        log.info("Budget initialized taskId={} tenantId={} maxTokens={} maxCost={} maxAttempts={} maxExecutionMs={}",
                taskId, tenantId, tokens, cost, attempts, executionMs);
        return saved;
    }

    /**
     * Checks whether the task has sufficient budget and execution time to proceed.
     *
     * <p>Denies if:
     * <ul>
     *   <li>Budget record does not exist</li>
     *   <li>Status is not ACTIVE</li>
     *   <li>Token budget is exhausted</li>
     *   <li>Cost budget is exhausted</li>
     *   <li>Attempt budget is exhausted</li>
     *   <li>Execution time limit (maxExecutionMs) is exceeded</li>
     * </ul>
     * </p>
     */
    @Transactional(readOnly = true)
    public BudgetCheckResult check(String tenantId, String taskId) {
        Budget budget = budgetRepository.findByTenantIdAndTaskId(tenantId, taskId).orElse(null);
        if (budget == null) {
            log.warn("Budget record missing — denying execution taskId={} tenantId={}", taskId, tenantId);
            return BudgetCheckResult.deny("No budget record found for task " + taskId
                    + ". Every task requires an initialised budget.");
        }

        if (!"ACTIVE".equalsIgnoreCase(budget.getStatus())) {
            log.warn("Budget is not ACTIVE taskId={} status={}", taskId, budget.getStatus());
            auditBudget(budget, "INACTIVE_BUDGET");
            return BudgetCheckResult.deny("Budget status is not ACTIVE: " + budget.getStatus());
        }

        if (budget.isTokenBudgetExhausted()) {
            log.warn("Budget token limit exhausted taskId={} used={} max={}",
                    taskId, budget.getUsedTokens(), budget.getMaxTokens());
            auditBudget(budget, "TOKEN_LIMIT_EXCEEDED");
            return BudgetCheckResult.deny("Token limit exceeded: used="
                    + budget.getUsedTokens() + " max=" + budget.getMaxTokens());
        }

        if (budget.isCostBudgetExhausted()) {
            log.warn("Budget cost limit exhausted taskId={} used={} max={}",
                    taskId, budget.getUsedCost(), budget.getMaxCost());
            auditBudget(budget, "COST_LIMIT_EXCEEDED");
            return BudgetCheckResult.deny("Cost limit exceeded: used="
                    + budget.getUsedCost() + " max=" + budget.getMaxCost());
        }

        if (budget.isAttemptBudgetExhausted()) {
            log.warn("Budget attempt limit exhausted taskId={} used={} max={}",
                    taskId, budget.getCurrentAttempts(), budget.getMaxAttempts());
            auditBudget(budget, "ATTEMPT_LIMIT_EXCEEDED");
            return BudgetCheckResult.deny("Attempt limit exceeded: used="
                    + budget.getCurrentAttempts() + " max=" + budget.getMaxAttempts());
        }

        LocalDateTime now = LocalDateTime.now();
        if (budget.isExecutionTimeExhausted(now)) {
            long elapsed = budget.getElapsedExecutionMs(now);
            log.warn("Budget execution time exhausted taskId={} elapsed={}ms max={}ms",
                    taskId, elapsed, budget.getMaxExecutionMs());
            auditBudget(budget, "EXECUTION_TIME_EXCEEDED");
            return BudgetCheckResult.deny("Execution time limit exceeded: elapsed="
                    + elapsed + "ms max=" + budget.getMaxExecutionMs() + "ms");
        }

        Integer remainingAttempts = budget.getMaxAttempts() != null
                ? budget.getMaxAttempts() - budget.getCurrentAttempts() : null;
        return BudgetCheckResult.allow(budget.remainingTokens(), budget.remainingCost(), remainingAttempts);
    }

    /**
     * Enforces budget — throws {@link BudgetExceededException} if exhausted or missing.
     */
    public void enforce(String tenantId, String taskId) {
        BudgetCheckResult result = check(tenantId, taskId);
        if (!result.allowed()) {
            throw new BudgetExceededException("Budget exhausted for task " + taskId
                    + ": " + result.exhaustedReason());
        }
    }

    /**
     * Records consumption of tokens and cost.
     * Uses tenant-scoped pessimistic lock.
     */
    @Transactional
    public void consume(String tenantId, String taskId, long tokensUsed, BigDecimal costIncurred) {
        if (tokensUsed < 0) {
            throw new IllegalArgumentException("tokensUsed must not be negative, got: " + tokensUsed);
        }
        if (costIncurred != null && costIncurred.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("costIncurred must not be negative, got: " + costIncurred);
        }

        Budget budget = budgetRepository.findByTenantIdAndTaskIdForUpdate(tenantId, taskId).orElse(null);
        if (budget == null) {
            log.error("Cannot consume budget — record missing taskId={} tenantId={}", taskId, tenantId);
            throw new BudgetExceededException("No budget record found for task " + taskId);
        }

        if (!"ACTIVE".equalsIgnoreCase(budget.getStatus())) {
            log.error("Cannot consume budget — budget not active taskId={} status={}", taskId, budget.getStatus());
            throw new BudgetExceededException("Budget is not active for task " + taskId);
        }

        budget.setUsedTokens((budget.getUsedTokens() != null ? budget.getUsedTokens() : 0L) + tokensUsed);
        BigDecimal current = budget.getUsedCost() != null ? budget.getUsedCost() : BigDecimal.ZERO;
        budget.setUsedCost(current.add(costIncurred != null ? costIncurred : BigDecimal.ZERO));
        budget.setUpdatedAt(LocalDateTime.now());
        budgetRepository.save(budget);

        log.debug("Budget consumed taskId={} tokens={} cost={} totalTokens={} totalCost={}",
                taskId, tokensUsed, costIncurred, budget.getUsedTokens(), budget.getUsedCost());
        auditBudget(budget, "CONSUMED");
    }

    /**
     * Increments the attempt counter. Enforces attempt limit atomically under write lock.
     */
    @Transactional
    public void incrementAttempts(String tenantId, String taskId) {
        Budget budget = budgetRepository.findByTenantIdAndTaskIdForUpdate(tenantId, taskId).orElse(null);
        if (budget == null) {
            log.error("Cannot increment attempts — budget record missing taskId={} tenantId={}", taskId, tenantId);
            throw new BudgetExceededException("No budget record found for task " + taskId);
        }

        int current = (budget.getCurrentAttempts() != null ? budget.getCurrentAttempts() : 0);
        int max = (budget.getMaxAttempts() != null ? budget.getMaxAttempts() : budgetProperties.getDefaultMaxAttempts());
        if (current >= max) {
            log.warn("Cannot increment attempts — limit reached taskId={} current={} max={}", taskId, current, max);
            throw new BudgetExceededException("Cannot increment attempts: attempt limit reached (" + current + "/" + max + ")");
        }

        budget.setCurrentAttempts(current + 1);
        budget.setUpdatedAt(LocalDateTime.now());
        budgetRepository.save(budget);

        log.info("Budget attempt incremented taskId={} currentAttempts={} maxAttempts={}",
                taskId, budget.getCurrentAttempts(), budget.getMaxAttempts());
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private void auditBudget(Budget budget, String event) {
        String metadata;
        try {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("event", event);
            node.put("usedTokens", budget.getUsedTokens() != null ? budget.getUsedTokens() : 0L);
            node.put("usedCost",
                    budget.getUsedCost() != null ? budget.getUsedCost().toPlainString() : "0");
            node.put("currentAttempts",
                    budget.getCurrentAttempts() != null ? budget.getCurrentAttempts() : 0);
            metadata = objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            log.error("Failed to serialize budget audit metadata: {}", e.getMessage(), e);
            metadata = "{\"error\":\"serialization_failed\"}";
        }
        auditService.record(budget.getTenantId(), AuditEventType.BUDGET_CHECKED,
                "BUDGET_MANAGER", budget.getTaskId(), null, null, metadata);
    }
}
