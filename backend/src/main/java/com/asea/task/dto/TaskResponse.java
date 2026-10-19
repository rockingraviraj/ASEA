package com.asea.task.dto;

import com.asea.state.TaskState;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record TaskResponse(
        Long id,
        String taskId,
        String tenantId,
        Long repositoryId,
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
        BigDecimal budgetCost,
        Integer maxAttempts,
        Long maxExecutionMs,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime completedAt,
        String metadata) {
}