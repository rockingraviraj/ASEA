package com.asea.retry;

/**
 * Classification of failure types to drive recovery strategy.
 *
 * <p>The retry strategy depends on the failure classification.
 * Different failures warrant different recovery approaches.</p>
 */
public enum FailureType {
    /** Test failure — retry with improved implementation */
    TEST_FAILURE,
    /** Compilation error — retry with corrected code */
    COMPILATION_ERROR,
    /** Validation failure — retry with corrected approach */
    VALIDATION_FAILURE,
    /** Static analysis failure — retry with code improvements */
    STATIC_ANALYSIS_FAILURE,
    /** Security scan failure — retry with security fixes */
    SECURITY_FAILURE,
    /** CI pipeline failure — retry with build fixes */
    CI_FAILURE,
    /** LLM produced invalid/unusable output */
    LLM_QUALITY_FAILURE,
    /** LLM request timed out */
    LLM_TIMEOUT,
    /** External service (GitHub, etc.) unavailable */
    INTEGRATION_FAILURE,
    /** Budget exhausted — escalate, do not retry */
    BUDGET_EXHAUSTED,
    /** Unknown failure type */
    UNKNOWN
}
