package com.asea.task.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Request payload for creating a task.
 *
 * <p><b>Tenant Isolation:</b> Tenant ownership is strictly derived server-side from
 * the authenticated principal (via {@link com.asea.security.TenantResolver}).
 * If a legacy {@code tenantId} field is provided in the request body, it is treated strictly
 * as an optional assertion. If present and matching the authenticated tenant, it is accepted;
 * if it differs from the authenticated tenant, the request is rejected with HTTP 403 Forbidden.
 * It NEVER determines or overrides task ownership.</p>
 */
public record TaskCreateRequest(

        Long repositoryId,

        @Size(max = 500, message = "issueUrl must be at most 500 characters")
        String issueUrl,

        @NotBlank(message = "title is required")
        @Size(max = 500, message = "title must be at most 500 characters")
        String title,

        String description,

        String trigger,

        @NotBlank(message = "taskType is required")
        @Size(max = 50, message = "taskType must be at most 50 characters")
        String taskType,

        @Min(value = 0, message = "priority must be >= 0")
        Integer priority,

        @Min(value = 1, message = "budgetTokens must be >= 1")
        Long budgetTokens,

        @DecimalMin(value = "0.0", inclusive = false, message = "budgetCost must be positive")
        BigDecimal budgetCost,

        @Min(value = 1, message = "maxAttempts must be >= 1")
        @Max(value = 10, message = "maxAttempts must be <= 10")
        Integer maxAttempts,

        @Min(value = 1000, message = "maxExecutionMs must be at least 1000ms")
        Long maxExecutionMs,

        String metadata,

        @Schema(description = "Optional legacy tenant assertion. If supplied, must match the authenticated tenant; mismatch returns 403. Does not determine ownership.")
        @JsonProperty("tenantId")
        String tenantAssertion) {

    public TaskCreateRequest(
            Long repositoryId,
            String issueUrl,
            String title,
            String description,
            String trigger,
            String taskType,
            Integer priority,
            Long budgetTokens,
            BigDecimal budgetCost,
            Integer maxAttempts,
            Long maxExecutionMs,
            String metadata) {
        this(repositoryId, issueUrl, title, description, trigger, taskType,
             priority, budgetTokens, budgetCost, maxAttempts, maxExecutionMs, metadata, null);
    }
}