package com.asea.task;

import com.asea.state.TaskState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import jakarta.validation.constraints.NotBlank;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Core domain entity representing a software-engineering task submitted to ASEA.
 *
 * <p>Uses optimistic locking (@Version) to prevent concurrent state corruption.
 * Never expose this entity directly through REST — always use {@link com.asea.task.dto.TaskResponse}.</p>
 */
@Entity
@Table(name = "tasks", indexes = {
        @Index(name = "idx_tasks_tenant", columnList = "tenant_id"),
        @Index(name = "idx_tasks_status", columnList = "status"),
        @Index(name = "idx_tasks_repo", columnList = "repository_id")
})
@EntityListeners(AuditingEntityListener.class)
public class Task {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Version
    @Column(name = "version")
    private Long version;

    @Column(name = "tenant_id", nullable = false, length = 50)
    private String tenantId;

    @Column(name = "task_id", nullable = false, unique = true, length = 100)
    private String taskId;

    @Column(name = "repository_id")
    private Long repositoryId;

    @NotBlank
    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "issue_url", length = 500)
    private String issueUrl;

    @Column(name = "trigger", length = 50)
    private String trigger;

    @NotBlank
    @Column(name = "task_type", nullable = false, length = 50)
    private String taskType;

    @Column(name = "priority")
    private Integer priority = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    private TaskState status = TaskState.CREATED;

    @Enumerated(EnumType.STRING)
    @Column(name = "current_state", nullable = false, length = 50)
    private TaskState currentState = TaskState.CREATED;

    @Column(name = "assigned_agent", length = 100)
    private String assignedAgent;

    @Column(name = "budget_tokens")
    private Long budgetTokens;

    @Column(name = "budget_cost", precision = 12, scale = 4)
    private BigDecimal budgetCost;

    @Column(name = "max_attempts")
    private Integer maxAttempts = 3;

    @Column(name = "max_execution_ms")
    private Long maxExecutionMs;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    private String metadata;

    public Task() {
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }

    public Long getRepositoryId() { return repositoryId; }
    public void setRepositoryId(Long repositoryId) { this.repositoryId = repositoryId; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getIssueUrl() { return issueUrl; }
    public void setIssueUrl(String issueUrl) { this.issueUrl = issueUrl; }

    public String getTrigger() { return trigger; }
    public void setTrigger(String trigger) { this.trigger = trigger; }

    public String getTaskType() { return taskType; }
    public void setTaskType(String taskType) { this.taskType = taskType; }

    public Integer getPriority() { return priority; }
    public void setPriority(Integer priority) { this.priority = priority; }

    public TaskState getStatus() { return status; }
    public void setStatus(TaskState status) { this.status = status; }

    public TaskState getCurrentState() { return currentState; }
    public void setCurrentState(TaskState currentState) { this.currentState = currentState; }

    public String getAssignedAgent() { return assignedAgent; }
    public void setAssignedAgent(String assignedAgent) { this.assignedAgent = assignedAgent; }

    public Long getBudgetTokens() { return budgetTokens; }
    public void setBudgetTokens(Long budgetTokens) { this.budgetTokens = budgetTokens; }

    public BigDecimal getBudgetCost() { return budgetCost; }
    public void setBudgetCost(BigDecimal budgetCost) { this.budgetCost = budgetCost; }

    public Integer getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(Integer maxAttempts) { this.maxAttempts = maxAttempts; }

    public Long getMaxExecutionMs() { return maxExecutionMs; }
    public void setMaxExecutionMs(Long maxExecutionMs) { this.maxExecutionMs = maxExecutionMs; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }

    public String getMetadata() { return metadata; }
    public void setMetadata(String metadata) { this.metadata = metadata; }
}