package com.asea.state;

import com.asea.common.exceptions.InvalidTransitionException;

import java.util.Set;

/**
 * Defines the legal transitions between {@link TaskState} values.
 *
 * <p>Recovery loops are first-class: a failed test can drive the task back through
 * RECOVERING -> DIAGNOSING -> PLANNING -> IMPLEMENTING instead of terminating it.</p>
 */
public final class StateGraph {

    private StateGraph() {
    }

    /**
     * @return true if the transition is permitted by the state graph.
     */
    public static boolean canTransition(TaskState from, TaskState to) {
        if (from == null || to == null) {
            return false;
        }
        if (from == to) {
            return false;
        }
        return allowedTransitions(from).contains(to);
    }

    /**
     * @return the set of states reachable from the given state.
     */
    public static Set<TaskState> allowedTransitions(TaskState from) {
        return switch (from) {
            case CREATED -> Set.of(
                    TaskState.UNDERSTANDING,
                    TaskState.FAILED,
                    TaskState.ESCALATED);
            case UNDERSTANDING -> Set.of(
                    TaskState.ANALYZING,
                    TaskState.DIAGNOSING,
                    TaskState.FAILED,
                    TaskState.ESCALATED);
            case ANALYZING -> Set.of(
                    TaskState.DIAGNOSING,
                    TaskState.FAILED,
                    TaskState.ESCALATED);
            case DIAGNOSING -> Set.of(
                    TaskState.PLANNING,
                    TaskState.FAILED,
                    TaskState.ESCALATED);
            case PLANNING -> Set.of(
                    TaskState.IMPLEMENTING,
                    TaskState.FAILED,
                    TaskState.ESCALATED);
            case IMPLEMENTING -> Set.of(
                    TaskState.TESTING,
                    TaskState.FAILED,
                    TaskState.ESCALATED);
            case TESTING -> Set.of(
                    TaskState.VALIDATING,
                    TaskState.RECOVERING,
                    TaskState.FAILED,
                    TaskState.ESCALATED);
            case VALIDATING -> Set.of(
                    TaskState.REVIEWING,
                    TaskState.SECURITY_CHECK,
                    TaskState.RECOVERING,
                    TaskState.FAILED,
                    TaskState.ESCALATED);
            case RECOVERING -> Set.of(
                    TaskState.DIAGNOSING,
                    TaskState.PLANNING,
                    TaskState.FAILED,
                    TaskState.ESCALATED);
            case REVIEWING -> Set.of(
                    TaskState.SECURITY_CHECK,
                    TaskState.CI_VALIDATION,
                    TaskState.RECOVERING,
                    TaskState.FAILED,
                    TaskState.ESCALATED);
            case SECURITY_CHECK -> Set.of(
                    TaskState.CI_VALIDATION,
                    TaskState.RECOVERING,
                    TaskState.FAILED,
                    TaskState.ESCALATED);
            case CI_VALIDATION -> Set.of(
                    TaskState.PR_READY,
                    TaskState.RECOVERING,
                    TaskState.FAILED,
                    TaskState.ESCALATED);
            case PR_READY -> Set.of(
                    TaskState.HUMAN_REVIEW,
                    TaskState.COMPLETED,
                    TaskState.FAILED,
                    TaskState.ESCALATED);
            case HUMAN_REVIEW -> Set.of(
                    TaskState.COMPLETED,
                    TaskState.FAILED,
                    TaskState.ESCALATED);
            case COMPLETED, FAILED, ESCALATED -> Set.of();
        };
    }

    /**
     * @throws InvalidTransitionException if the transition is not permitted.
     */
    public static void validate(TaskState from, TaskState to) {
        if (!canTransition(from, to)) {
            throw new InvalidTransitionException(
                    "Illegal state transition: " + from + " -> " + to);
        }
    }

    /**
     * @return the recommended recovery state after a failure in the given state.
     */
    public static TaskState recoveryState(TaskState failedAt) {
        return switch (failedAt) {
            case TESTING, VALIDATING, REVIEWING, SECURITY_CHECK, CI_VALIDATION
                    -> TaskState.RECOVERING;
            default -> TaskState.DIAGNOSING;
        };
    }
}