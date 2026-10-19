package com.asea.state;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Formal task state graph for ASEA.
 *
 * <p>Not a linear pipeline. The graph supports forward progress, recovery loops
 * (TESTING -> RECOVERING -> DIAGNOSING -> PLANNING -> IMPLEMENTING), and terminal
 * outcomes (COMPLETED / FAILED / ESCALATED).</p>
 */
public enum TaskState {

    CREATED("CREATED"),
    UNDERSTANDING("UNDERSTANDING"),
    ANALYZING("ANALYZING"),
    DIAGNOSING("DIAGNOSING"),
    PLANNING("PLANNING"),
    IMPLEMENTING("IMPLEMENTING"),
    TESTING("TESTING"),
    VALIDATING("VALIDATING"),
    RECOVERING("RECOVERING"),
    REVIEWING("REVIEWING"),
    SECURITY_CHECK("SECURITY_CHECK"),
    CI_VALIDATION("CI_VALIDATION"),
    PR_READY("PR_READY"),
    HUMAN_REVIEW("HUMAN_REVIEW"),
    COMPLETED("COMPLETED"),
    FAILED("FAILED"),
    ESCALATED("ESCALATED");

    private final String value;

    TaskState(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    public static TaskState fromValue(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        for (TaskState state : values()) {
            if (state.value.equalsIgnoreCase(value.trim())) {
                return state;
            }
        }
        return null;
    }

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == ESCALATED;
    }
}