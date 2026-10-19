package com.asea.task.dto;

import com.asea.state.TaskState;
import jakarta.validation.constraints.Size;

public record TaskUpdateRequest(
        String title,
        String description,
        String issueUrl,
        String trigger,
        String taskType,
        Integer priority,
        TaskState status,
        TaskState currentState,
        String assignedAgent,
        Long budgetTokens,
        Long budgetCost,
        Integer maxAttempts,
        Long maxExecutionMs,
        String metadata) {
}