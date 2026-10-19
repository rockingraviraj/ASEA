package com.asea.task.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

public record TaskCancelRequest(
        @Schema(description = "Reason for task cancellation", example = "User requested cancellation")
        @Size(max = 500)
        String reason
) {}
