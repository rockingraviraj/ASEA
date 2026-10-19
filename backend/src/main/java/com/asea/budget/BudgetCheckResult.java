package com.asea.budget;

import java.math.BigDecimal;

/**
 * Result of a budget check before an expensive operation.
 *
 * @param allowed        whether the operation may proceed
 * @param exhaustedReason human-readable reason if denied
 * @param remainingTokens remaining token budget (null if no limit)
 * @param remainingCost   remaining cost budget (null if no limit)
 * @param remainingAttempts remaining attempt budget (null if no limit)
 */
public record BudgetCheckResult(
        boolean allowed,
        String exhaustedReason,
        Long remainingTokens,
        BigDecimal remainingCost,
        Integer remainingAttempts) {

    public static BudgetCheckResult allow(Long remainingTokens, BigDecimal remainingCost,
                                          Integer remainingAttempts) {
        return new BudgetCheckResult(true, null, remainingTokens, remainingCost, remainingAttempts);
    }

    public static BudgetCheckResult deny(String reason) {
        return new BudgetCheckResult(false, reason, null, null, null);
    }
}
