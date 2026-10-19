package com.asea.state;

import com.asea.common.exceptions.InvalidTransitionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * Unit tests for {@link StateGraph} — validates the ASEA formal state machine.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Valid transitions along the happy path</li>
 *   <li>Invalid transitions that must be rejected</li>
 *   <li>Terminal state behaviour</li>
 *   <li>Recovery loop paths (TESTING → RECOVERING → DIAGNOSING → IMPLEMENTING)</li>
 *   <li>Escalation paths from any non-terminal state</li>
 *   <li>Human-review paths</li>
 * </ul>
 * </p>
 */
@DisplayName("StateGraph")
class StateGraphTest {

    // -------------------------------------------------------------------------
    // Happy path transitions
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("CREATED → UNDERSTANDING is allowed")
    void createdToUnderstanding() {
        assertThat(StateGraph.canTransition(TaskState.CREATED, TaskState.UNDERSTANDING)).isTrue();
    }

    @Test
    @DisplayName("Full happy path — CREATED to PR_READY")
    void fullHappyPath() {
        TaskState[] path = {
            TaskState.CREATED,
            TaskState.UNDERSTANDING,
            TaskState.ANALYZING,
            TaskState.DIAGNOSING,
            TaskState.PLANNING,
            TaskState.IMPLEMENTING,
            TaskState.TESTING,
            TaskState.VALIDATING,
            TaskState.REVIEWING,
            TaskState.SECURITY_CHECK,
            TaskState.CI_VALIDATION,
            TaskState.PR_READY
        };
        for (int i = 0; i < path.length - 1; i++) {
            assertThat(StateGraph.canTransition(path[i], path[i + 1]))
                    .as("Expected %s → %s to be allowed", path[i], path[i + 1])
                    .isTrue();
        }
    }

    @Test
    @DisplayName("PR_READY → HUMAN_REVIEW is allowed")
    void prReadyToHumanReview() {
        assertThat(StateGraph.canTransition(TaskState.PR_READY, TaskState.HUMAN_REVIEW)).isTrue();
    }

    @Test
    @DisplayName("PR_READY → COMPLETED is allowed (auto-merge path)")
    void prReadyToCompleted() {
        assertThat(StateGraph.canTransition(TaskState.PR_READY, TaskState.COMPLETED)).isTrue();
    }

    @Test
    @DisplayName("HUMAN_REVIEW → COMPLETED is allowed")
    void humanReviewToCompleted() {
        assertThat(StateGraph.canTransition(TaskState.HUMAN_REVIEW, TaskState.COMPLETED)).isTrue();
    }

    @Test
    @DisplayName("HUMAN_REVIEW → FAILED is allowed")
    void humanReviewToFailed() {
        assertThat(StateGraph.canTransition(TaskState.HUMAN_REVIEW, TaskState.FAILED)).isTrue();
    }

    // -------------------------------------------------------------------------
    // Recovery loop
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("TESTING → RECOVERING is allowed (validation failure path)")
    void testingToRecovering() {
        assertThat(StateGraph.canTransition(TaskState.TESTING, TaskState.RECOVERING)).isTrue();
    }

    @Test
    @DisplayName("VALIDATING → RECOVERING is allowed")
    void validatingToRecovering() {
        assertThat(StateGraph.canTransition(TaskState.VALIDATING, TaskState.RECOVERING)).isTrue();
    }

    @Test
    @DisplayName("RECOVERING → DIAGNOSING is allowed (loop back)")
    void recoveringToDiagnosing() {
        assertThat(StateGraph.canTransition(TaskState.RECOVERING, TaskState.DIAGNOSING)).isTrue();
    }

    @Test
    @DisplayName("RECOVERING → PLANNING is allowed (skip diagnose when cause is known)")
    void recoveringToPlanning() {
        assertThat(StateGraph.canTransition(TaskState.RECOVERING, TaskState.PLANNING)).isTrue();
    }

    @Test
    @DisplayName("Full recovery loop: TESTING → RECOVERING → DIAGNOSING → PLANNING → IMPLEMENTING")
    void fullRecoveryLoop() {
        assertThat(StateGraph.canTransition(TaskState.TESTING, TaskState.RECOVERING)).isTrue();
        assertThat(StateGraph.canTransition(TaskState.RECOVERING, TaskState.DIAGNOSING)).isTrue();
        assertThat(StateGraph.canTransition(TaskState.DIAGNOSING, TaskState.PLANNING)).isTrue();
        assertThat(StateGraph.canTransition(TaskState.PLANNING, TaskState.IMPLEMENTING)).isTrue();
    }

    // -------------------------------------------------------------------------
    // Escalation paths
    // -------------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(value = TaskState.class, names = {"COMPLETED", "FAILED", "ESCALATED"}, mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("Any non-terminal state can transition to ESCALATED")
    void anyNonTerminalCanEscalate(TaskState state) {
        assertThat(StateGraph.canTransition(state, TaskState.ESCALATED))
                .as("Expected %s → ESCALATED to be allowed", state)
                .isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = TaskState.class, names = {"COMPLETED", "FAILED", "ESCALATED"}, mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("Any non-terminal state can transition to FAILED")
    void anyNonTerminalCanFail(TaskState state) {
        if (state == TaskState.PR_READY) return; // PR_READY cannot transition to FAILED directly
        assertThat(StateGraph.canTransition(state, TaskState.FAILED))
                .as("Expected %s → FAILED to be allowed", state)
                .isTrue();
    }

    // -------------------------------------------------------------------------
    // Invalid transitions
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Same-state transition is never allowed")
    void sameStateTransitionDenied() {
        for (TaskState state : TaskState.values()) {
            assertThat(StateGraph.canTransition(state, state))
                    .as("Self-transition should be denied for %s", state)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("CREATED → IMPLEMENTING is not allowed (must go through intermediate states)")
    void createdToImplementingDenied() {
        assertThat(StateGraph.canTransition(TaskState.CREATED, TaskState.IMPLEMENTING)).isFalse();
    }

    @Test
    @DisplayName("COMPLETED → UNDERSTANDING is not allowed (completed is terminal)")
    void completedToUnderstandingDenied() {
        assertThat(StateGraph.canTransition(TaskState.COMPLETED, TaskState.UNDERSTANDING)).isFalse();
    }

    @Test
    @DisplayName("FAILED → any state is not allowed (failed is terminal)")
    void failedToAnyStateDenied() {
        for (TaskState state : TaskState.values()) {
            assertThat(StateGraph.canTransition(TaskState.FAILED, state))
                    .as("Transition from FAILED to %s should be denied", state)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("ESCALATED → any state is not allowed (escalated is terminal)")
    void escalatedToAnyStateDenied() {
        for (TaskState state : TaskState.values()) {
            assertThat(StateGraph.canTransition(TaskState.ESCALATED, state))
                    .as("Transition from ESCALATED to %s should be denied", state)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("null → any state returns false (no NPE)")
    void nullFromStateReturnsFalse() {
        assertThat(StateGraph.canTransition(null, TaskState.CREATED)).isFalse();
    }

    @Test
    @DisplayName("any state → null returns false (no NPE)")
    void nullToStateReturnsFalse() {
        assertThat(StateGraph.canTransition(TaskState.CREATED, null)).isFalse();
    }

    // -------------------------------------------------------------------------
    // Terminal states
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("COMPLETED, FAILED, ESCALATED are terminal states")
    void terminalStates() {
        assertThat(TaskState.COMPLETED.isTerminal()).isTrue();
        assertThat(TaskState.FAILED.isTerminal()).isTrue();
        assertThat(TaskState.ESCALATED.isTerminal()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = TaskState.class, names = {"COMPLETED", "FAILED", "ESCALATED"}, mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("Non-terminal states are not terminal")
    void nonTerminalStates(TaskState state) {
        assertThat(state.isTerminal()).isFalse();
    }

    // -------------------------------------------------------------------------
    // validate() method
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("validate() throws InvalidTransitionException for illegal transitions")
    void validateThrowsForIllegalTransition() {
        assertThatThrownBy(() -> StateGraph.validate(TaskState.CREATED, TaskState.COMPLETED))
                .isInstanceOf(InvalidTransitionException.class)
                .hasMessageContaining("CREATED")
                .hasMessageContaining("COMPLETED");
    }

    @Test
    @DisplayName("validate() does not throw for legal transitions")
    void validateNoThrowForLegalTransition() {
        assertThatNoException().isThrownBy(
                () -> StateGraph.validate(TaskState.CREATED, TaskState.UNDERSTANDING));
    }

    // -------------------------------------------------------------------------
    // recoveryState()
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("recoveryState() returns RECOVERING for testing/validation states")
    void recoveryStateForTestingStates() {
        assertThat(StateGraph.recoveryState(TaskState.TESTING)).isEqualTo(TaskState.RECOVERING);
        assertThat(StateGraph.recoveryState(TaskState.VALIDATING)).isEqualTo(TaskState.RECOVERING);
        assertThat(StateGraph.recoveryState(TaskState.REVIEWING)).isEqualTo(TaskState.RECOVERING);
        assertThat(StateGraph.recoveryState(TaskState.SECURITY_CHECK)).isEqualTo(TaskState.RECOVERING);
        assertThat(StateGraph.recoveryState(TaskState.CI_VALIDATION)).isEqualTo(TaskState.RECOVERING);
    }

    @Test
    @DisplayName("recoveryState() returns DIAGNOSING for early-stage states")
    void recoveryStateForEarlyStates() {
        assertThat(StateGraph.recoveryState(TaskState.IMPLEMENTING)).isEqualTo(TaskState.DIAGNOSING);
        assertThat(StateGraph.recoveryState(TaskState.PLANNING)).isEqualTo(TaskState.DIAGNOSING);
    }

    // -------------------------------------------------------------------------
    // allowedTransitions() completeness
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("allowedTransitions() is defined for every TaskState value")
    void allowedTransitionsDefinedForAllStates() {
        for (TaskState state : TaskState.values()) {
            Set<TaskState> transitions = StateGraph.allowedTransitions(state);
            assertThat(transitions).isNotNull();
            // Terminal states have no outbound transitions
            if (state.isTerminal()) {
                assertThat(transitions).isEmpty();
            }
        }
    }
}
