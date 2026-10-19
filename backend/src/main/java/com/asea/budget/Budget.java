package com.asea.budget;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Tracks budget consumption for a task.
 *
 * <p>Uses optimistic locking (@Version) to prevent race conditions when
 * multiple operations concurrently consume budget.</p>
 */
@Entity
@Table(name = "budgets", indexes = {
        @Index(name = "idx_budgets_task", columnList = "task_id"),
        @Index(name = "idx_budgets_tenant", columnList = "tenant_id")
})
public class Budget {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    @Column(name = "version")
    private Long version;

    @Column(name = "tenant_id", nullable = false, length = 50)
    private String tenantId;

    @Column(name = "task_id", nullable = false, unique = true, length = 100)
    private String taskId;

    @Column(name = "max_tokens")
    private Long maxTokens;

    @Column(name = "max_cost", precision = 12, scale = 4)
    private BigDecimal maxCost;

    @Column(name = "max_attempts")
    private Integer maxAttempts = 3;

    @Column(name = "max_execution_ms")
    private Long maxExecutionMs;

    @Column(name = "used_tokens")
    private Long usedTokens = 0L;

    @Column(name = "used_cost", precision = 12, scale = 4)
    private BigDecimal usedCost = BigDecimal.ZERO;

    @Column(name = "current_attempts")
    private Integer currentAttempts = 0;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "status", nullable = false, length = 20)
    private String status = "ACTIVE";

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public Budget() {
    }

    public Long getId() { return id; }
    public Long getVersion() { return version; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public Long getMaxTokens() { return maxTokens; }
    public void setMaxTokens(Long maxTokens) { this.maxTokens = maxTokens; }
    public BigDecimal getMaxCost() { return maxCost; }
    public void setMaxCost(BigDecimal maxCost) { this.maxCost = maxCost; }
    public Integer getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(Integer maxAttempts) { this.maxAttempts = maxAttempts; }
    public Long getMaxExecutionMs() { return maxExecutionMs; }
    public void setMaxExecutionMs(Long maxExecutionMs) { this.maxExecutionMs = maxExecutionMs; }
    public Long getUsedTokens() { return usedTokens; }
    public void setUsedTokens(Long usedTokens) { this.usedTokens = usedTokens; }
    public BigDecimal getUsedCost() { return usedCost; }
    public void setUsedCost(BigDecimal usedCost) { this.usedCost = usedCost; }
    public Integer getCurrentAttempts() { return currentAttempts; }
    public void setCurrentAttempts(Integer currentAttempts) { this.currentAttempts = currentAttempts; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    /** @return remaining tokens, or null if no token limit was set */
    public Long remainingTokens() {
        if (maxTokens == null) return null;
        return maxTokens - (usedTokens != null ? usedTokens : 0L);
    }

    /** @return true if the token budget is exhausted */
    public boolean isTokenBudgetExhausted() {
        if (maxTokens == null) return false;
        return (usedTokens != null ? usedTokens : 0L) >= maxTokens;
    }

    /** @return remaining cost, or null if no cost limit was set */
    public BigDecimal remainingCost() {
        if (maxCost == null) return null;
        BigDecimal used = usedCost != null ? usedCost : BigDecimal.ZERO;
        BigDecimal remaining = maxCost.subtract(used);
        return remaining.compareTo(BigDecimal.ZERO) > 0 ? remaining : BigDecimal.ZERO;
    }

    /** @return true if the cost budget is exhausted */
    public boolean isCostBudgetExhausted() {
        if (maxCost == null) return false;
        BigDecimal used = usedCost != null ? usedCost : BigDecimal.ZERO;
        return used.compareTo(maxCost) >= 0;
    }

    /** @return true if maximum attempts have been reached */
    public boolean isAttemptBudgetExhausted() {
        if (maxAttempts == null) return false;
        return (currentAttempts != null ? currentAttempts : 0) >= maxAttempts;
    }

    /** @return elapsed milliseconds since startedAt */
    public long getElapsedExecutionMs(LocalDateTime now) {
        if (startedAt == null) return 0L;
        LocalDateTime ref = now != null ? now : LocalDateTime.now();
        return java.time.Duration.between(startedAt, ref).toMillis();
    }

    /** @return true if execution time limit has been exceeded */
    public boolean isExecutionTimeExhausted(LocalDateTime now) {
        if (maxExecutionMs == null || startedAt == null) return false;
        return getElapsedExecutionMs(now) >= maxExecutionMs;
    }

    /** @return true if any budget limit is exhausted */
    public boolean isExhausted() {
        return isTokenBudgetExhausted() || isCostBudgetExhausted()
                || isAttemptBudgetExhausted() || isExecutionTimeExhausted(LocalDateTime.now());
    }
}
