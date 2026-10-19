package com.asea.retry;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * Records a single retry attempt for a task.
 *
 * <p>Each retry attempt must store its hypothesis, plan, failure evidence,
 * and reason for retry. ASEA never retries blindly.</p>
 */
@Entity
@Table(name = "retry_attempts", indexes = {
        @Index(name = "idx_retry_task", columnList = "task_id"),
        @Index(name = "idx_retry_tenant", columnList = "tenant_id")
})
public class RetryAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false, length = 50)
    private String tenantId;

    @Column(name = "task_id", nullable = false, length = 100)
    private String taskId;

    @Column(name = "attempt_number", nullable = false)
    private Integer attemptNumber;

    @Column(name = "hypothesis", columnDefinition = "TEXT")
    private String hypothesis;

    @Column(name = "plan", columnDefinition = "TEXT")
    private String plan;

    @Column(name = "patch_ref", length = 255)
    private String patchRef;

    @Column(name = "failure", columnDefinition = "TEXT")
    private String failure;

    @Column(name = "failure_type", length = 50)
    private String failureType;

    @Column(name = "evidence", columnDefinition = "TEXT")
    private String evidence;

    @Column(name = "retry_reason", columnDefinition = "TEXT")
    private String retryReason;

    @Column(name = "status", nullable = false, length = 50)
    private String status = "PENDING";

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    public RetryAttempt() {
    }

    public Long getId() { return id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public Integer getAttemptNumber() { return attemptNumber; }
    public void setAttemptNumber(Integer attemptNumber) { this.attemptNumber = attemptNumber; }
    public String getHypothesis() { return hypothesis; }
    public void setHypothesis(String hypothesis) { this.hypothesis = hypothesis; }
    public String getPlan() { return plan; }
    public void setPlan(String plan) { this.plan = plan; }
    public String getPatchRef() { return patchRef; }
    public void setPatchRef(String patchRef) { this.patchRef = patchRef; }
    public String getFailure() { return failure; }
    public void setFailure(String failure) { this.failure = failure; }
    public String getFailureType() { return failureType; }
    public void setFailureType(String failureType) { this.failureType = failureType; }
    public String getEvidence() { return evidence; }
    public void setEvidence(String evidence) { this.evidence = evidence; }
    public String getRetryReason() { return retryReason; }
    public void setRetryReason(String retryReason) { this.retryReason = retryReason; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }
}
