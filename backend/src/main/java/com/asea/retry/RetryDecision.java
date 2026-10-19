package com.asea.retry;

/**
 * Result of retry evaluation.
 *
 * @param canRetry          true if retry is authorized
 * @param nextAttemptNumber the 1-based attempt number for the retry
 * @param reason            explanation or justification
 * @param shouldEscalate    true if retry limit is exhausted or repeated identical failures require human escalation
 */
public record RetryDecision(
        boolean canRetry,
        int nextAttemptNumber,
        String reason,
        boolean shouldEscalate) {

    public static RetryDecision allow(int nextAttemptNumber, String reason) {
        return new RetryDecision(true, nextAttemptNumber, reason, false);
    }

    public static RetryDecision escalate(int nextAttemptNumber, String reason) {
        return new RetryDecision(false, nextAttemptNumber, reason, true);
    }

    public static RetryDecision deny(String reason) {
        return new RetryDecision(false, 0, reason, false);
    }
}
