package com.asea.budget;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Server-side finite default limits and hard bounds for task budgets.
 */
@Component
@ConfigurationProperties(prefix = "asea.budget")
public class BudgetProperties {

    public static final long HARD_MAX_TOKENS = 10_000_000L;
    public static final BigDecimal HARD_MAX_COST = new BigDecimal("1000.00");
    public static final int HARD_MAX_ATTEMPTS = 10;
    public static final long HARD_MAX_EXECUTION_MS = 3_600_000L; // 1 hour

    private Long defaultMaxTokens = 100_000L;
    private BigDecimal defaultMaxCost = new BigDecimal("10.00");
    private Integer defaultMaxAttempts = 3;
    private Long defaultMaxExecutionMs = 300_000L; // 5 minutes

    public Long getDefaultMaxTokens() {
        return defaultMaxTokens;
    }

    public void setDefaultMaxTokens(Long defaultMaxTokens) {
        this.defaultMaxTokens = defaultMaxTokens;
    }

    public BigDecimal getDefaultMaxCost() {
        return defaultMaxCost;
    }

    public void setDefaultMaxCost(BigDecimal defaultMaxCost) {
        this.defaultMaxCost = defaultMaxCost;
    }

    public Integer getDefaultMaxAttempts() {
        return defaultMaxAttempts;
    }

    public void setDefaultMaxAttempts(Integer defaultMaxAttempts) {
        this.defaultMaxAttempts = defaultMaxAttempts;
    }

    public Long getDefaultMaxExecutionMs() {
        return defaultMaxExecutionMs;
    }

    public void setDefaultMaxExecutionMs(Long defaultMaxExecutionMs) {
        this.defaultMaxExecutionMs = defaultMaxExecutionMs;
    }
}
