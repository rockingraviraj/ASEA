package com.asea.task.dto;

import com.asea.state.TaskState;

import java.time.LocalDateTime;

public record TaskHistoryResponse(
        Long id,
        String taskId,
        TaskState fromState,
        TaskState toState,
        String trigger,
        String reason,
        LocalDateTime createdAt) {
}