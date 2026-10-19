package com.asea.audit;

/**
 * Enumeration of all auditable event types in ASEA.
 *
 * <p>New event types must be added here; never use raw strings for event types.</p>
 */
public enum AuditEventType {

    // Task lifecycle
    TASK_CREATED,
    TASK_UPDATED,
    TASK_CANCELLED,
    TASK_COMPLETED,
    TASK_FAILED,
    TASK_ESCALATED,

    // State machine
    STATE_CHANGED,
    INVALID_TRANSITION_ATTEMPTED,

    // Orchestration
    ORCHESTRATOR_STARTED,
    ORCHESTRATOR_COMPLETED,
    WORKFLOW_SELECTED,

    // Agent execution
    AGENT_STARTED,
    AGENT_COMPLETED,
    AGENT_FAILED,
    AGENT_TIMEOUT,

    // Artifact
    ARTIFACT_PRODUCED,
    ARTIFACT_VALIDATED,
    ARTIFACT_REJECTED,

    // Tool invocation
    TOOL_INVOKED,
    TOOL_COMPLETED,
    TOOL_FAILED,

    // Policy evaluation
    POLICY_EVALUATED,
    POLICY_ALLOWED,
    POLICY_DENIED,
    POLICY_APPROVAL_REQUIRED,

    // Permission evaluation
    PERMISSION_GRANTED,
    PERMISSION_DENIED,

    // Patch
    PATCH_PROPOSED,
    PATCH_POLICY_CHECKED,
    PATCH_PERMISSION_CHECKED,
    PATCH_APPLIED,
    PATCH_REJECTED,

    // Validation
    VALIDATION_STARTED,
    VALIDATION_PASSED,
    VALIDATION_FAILED,

    // Retry/Recovery
    RETRY_INITIATED,
    RETRY_EXHAUSTED,
    RECOVERY_STARTED,
    ROLLBACK_EXECUTED,

    // Budget
    BUDGET_CHECKED,
    BUDGET_EXCEEDED,
    BUDGET_CONSUMED,

    // Human escalation
    HUMAN_APPROVAL_REQUESTED,
    HUMAN_APPROVAL_GRANTED,
    HUMAN_APPROVAL_REJECTED,
    HUMAN_APPROVAL_EXPIRED,

    // Evidence
    EVIDENCE_RECORDED,

    // Model
    MODEL_INVOKED,
    MODEL_RESPONDED,
    MODEL_FAILED,

    // GitHub integration
    GITHUB_BRANCH_CREATED,
    GITHUB_PR_CREATED,
    GITHUB_CI_CHECKED,

    // Sandbox
    SANDBOX_STARTED,
    SANDBOX_COMPLETED,
    SANDBOX_FAILED,

    // Security
    SECURITY_SCAN_STARTED,
    SECURITY_SCAN_COMPLETED,
    SECURITY_VIOLATION_DETECTED
}
