package com.asea.task.dto;

import com.asea.state.TaskState;
import jakarta.validation.constraints.NotNull;

public record TaskTransitionRequest(
        @NotNull(message = "toState is required")
        TaskState toState,

        String trigger,

        String reason) {
}