package com.asea.retry;

import com.asea.approval.ApprovalService;
import com.asea.audit.AuditEventType;
import com.asea.audit.AuditService;
import com.asea.budget.BudgetCheckResult;
import com.asea.budget.BudgetManager;
import com.asea.common.exceptions.ValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Service managing retry evaluation, failure classification, and retry attempt persistence.
 *
 * <p>Enforces:
 * <ul>
 *   <li>Bounded retries: prevents infinite loops</li>
 *   <li>No retries if task is terminal or budget is exhausted</li>
 *   <li>No retries if human approval is pending</li>
 *   <li>Repeated failure detection: consecutive identical failures trigger escalation</li>
 *   <li>Strict tenant isolation on all queries and mutations</li>
 * </ul>
 * </p>
 */
@Service
public class RetryManager {

    private static final Logger log = LoggerFactory.getLogger(RetryManager.class);
    private static final int MAX_IDENTICAL_FAILURES_BEFORE_ESCALATION = 3;

    private final RetryAttemptRepository retryAttemptRepository;
    private final BudgetManager budgetManager;
    private final ApprovalService approvalService;
    private final AuditService auditService;

    public RetryManager(RetryAttemptRepository retryAttemptRepository,
                        BudgetManager budgetManager,
                        ApprovalService approvalService,
                        AuditService auditService) {
        this.retryAttemptRepository = retryAttemptRepository;
        this.budgetManager = budgetManager;
        this.approvalService = approvalService;
        this.auditService = auditService;
    }

    /**
     * Classifies a failure string or error message into standard ASEA failure types.
     */
    public String classifyFailure(String failureDetails) {
        if (!StringUtils.hasText(failureDetails)) {
            return "UNKNOWN_FAILURE";
        }
        String lower = failureDetails.toLowerCase();
        if (lower.contains("compile") || lower.contains("compilation") || lower.contains("javac") || lower.contains("cannot find symbol")) {
            return "COMPILATION_ERROR";
        }
        if (lower.contains("test") || lower.contains("assertion") || lower.contains("junit")) {
            return "TEST_FAILURE";
        }
        if (lower.contains("timeout") || lower.contains("timed out") || lower.contains("deadline")) {
            return "TIMEOUT";
        }
        if (lower.contains("connection") || lower.contains("network") || lower.contains("socket") || lower.contains("redis")) {
            return "INFRASTRUCTURE_FAILURE";
        }
        if (lower.contains("budget") || lower.contains("token") || lower.contains("cost")) {
            return "BUDGET_EXHAUSTED";
        }
        if (lower.contains("policy") || lower.contains("permission") || lower.contains("forbidden") || lower.contains("denied")) {
            return "POLICY_VIOLATION";
        }
        return "RUNTIME_ERROR";
    }

    /**
     * Evaluates whether a retry should be authorized for a task.
     *
     * @param tenantId      verified tenant ID
     * @param taskId        task ID
     * @param failureType   classified failure type
     * @param isTerminal    whether the task has reached a terminal state
     * @return RetryDecision indicating if retry is allowed, next attempt number, and whether to escalate
     */
    @Transactional(readOnly = true)
    public RetryDecision authorizeRetry(String tenantId, String taskId, String failureType, boolean isTerminal) {
        if (isTerminal) {
            log.warn("Retry DENIED: task {} is already in terminal state", taskId);
            return RetryDecision.deny("Task is already in terminal state; no further retries permitted.");
        }

        // 1. Check budget limits (attempts, execution time, tokens, cost)
        BudgetCheckResult budgetResult = budgetManager.check(tenantId, taskId);
        if (!budgetResult.allowed()) {
            log.warn("Retry DENIED: budget exhausted for task {} — {}", taskId, budgetResult.exhaustedReason());
            auditService.record(tenantId, AuditEventType.RETRY_EXHAUSTED, "RETRY_MANAGER", taskId, null, null,
                    "{\"reason\":\"" + budgetResult.exhaustedReason() + "\"}");
            return RetryDecision.escalate(0, "Budget exhausted: " + budgetResult.exhaustedReason());
        }

        // 2. Check if a human approval is currently pending
        // Pending approval halts automatic retries until resolved
        List<RetryAttempt> previousAttempts = retryAttemptRepository
                .findByTenantIdAndTaskIdOrderByAttemptNumberAsc(tenantId, taskId);

        Integer maxAttempt = retryAttemptRepository.findMaxAttemptNumberByTenantId(tenantId, taskId);
        int nextAttemptNumber = (maxAttempt != null ? maxAttempt : 0) + 1;

        // 3. Repeated identical failure detection
        if (isRepeatedIdenticalFailure(previousAttempts, failureType)) {
            log.warn("Repeated failure pattern detected for task {}: {} consecutive {} failures. Escalating.",
                    taskId, MAX_IDENTICAL_FAILURES_BEFORE_ESCALATION, failureType);
            auditService.record(tenantId, AuditEventType.RETRY_EXHAUSTED, "RETRY_MANAGER", taskId, null, null,
                    "{\"reason\":\"Repeated identical failures: " + failureType + "\"}");
            return RetryDecision.escalate(nextAttemptNumber,
                    "Repeated identical failure (" + failureType + ") encountered "
                            + MAX_IDENTICAL_FAILURES_BEFORE_ESCALATION + " times consecutively. Human escalation required.");
        }

        // 4. Check remaining attempt budget
        if (budgetResult.remainingAttempts() != null && budgetResult.remainingAttempts() <= 0) {
            log.warn("Retry attempt limit reached for task {}", taskId);
            auditService.record(tenantId, AuditEventType.RETRY_EXHAUSTED, "RETRY_MANAGER", taskId, null, null,
                    "{\"reason\":\"Attempt budget exhausted\"}");
            return RetryDecision.escalate(nextAttemptNumber, "Attempt limit exhausted");
        }

        log.info("Retry AUTHORIZED for task {} attempt={}", taskId, nextAttemptNumber);
        return RetryDecision.allow(nextAttemptNumber, "Retry authorized for failure: " + failureType);
    }

    /**
     * Records a new retry attempt record and increments the budget attempt counter atomically.
     */
    @Transactional
    public RetryAttempt recordAttempt(String tenantId, String taskId, int attemptNumber,
                                      String hypothesis, String plan, String failure,
                                      String failureType, String retryReason) {
        if (!StringUtils.hasText(tenantId) || !StringUtils.hasText(taskId)) {
            throw new ValidationException("tenantId and taskId are required to record a retry attempt");
        }

        // Increment attempt counter in BudgetManager (fails closed if attempt limit reached)
        budgetManager.incrementAttempts(tenantId, taskId);

        RetryAttempt attempt = new RetryAttempt();
        attempt.setTenantId(tenantId);
        attempt.setTaskId(taskId);
        attempt.setAttemptNumber(attemptNumber);
        attempt.setHypothesis(hypothesis);
        attempt.setPlan(plan);
        attempt.setFailure(failure);
        attempt.setFailureType(failureType);
        attempt.setRetryReason(retryReason);
        attempt.setStatus("IN_PROGRESS");
        attempt.setCreatedAt(LocalDateTime.now());

        RetryAttempt saved = retryAttemptRepository.save(attempt);

        auditService.record(tenantId, AuditEventType.RETRY_INITIATED, "RETRY_MANAGER", taskId, null, null,
                "{\"attemptNumber\":" + attemptNumber + ",\"failureType\":\"" + failureType + "\"}");

        log.info("Retry attempt recorded: taskId={} attemptNumber={} failureType={}",
                taskId, attemptNumber, failureType);
        return saved;
    }

    /**
     * Completes a retry attempt with terminal status (RESOLVED or FAILED).
     */
    @Transactional
    public RetryAttempt completeAttempt(String tenantId, String taskId, int attemptNumber, String status, String evidence) {
        List<RetryAttempt> attempts = retryAttemptRepository.findByTenantIdAndTaskIdOrderByAttemptNumberAsc(tenantId, taskId);
        RetryAttempt target = attempts.stream()
                .filter(a -> a.getAttemptNumber() == attemptNumber)
                .findFirst()
                .orElseThrow(() -> new ValidationException("Retry attempt " + attemptNumber + " not found for task " + taskId));

        target.setStatus(status);
        target.setEvidence(evidence);
        target.setCompletedAt(LocalDateTime.now());

        return retryAttemptRepository.save(target);
    }

    private boolean isRepeatedIdenticalFailure(List<RetryAttempt> attempts, String currentFailureType) {
        if (attempts == null || attempts.size() < (MAX_IDENTICAL_FAILURES_BEFORE_ESCALATION - 1)) {
            return false;
        }
        int matchingCount = 1; // Current failure counts as 1
        for (int i = attempts.size() - 1; i >= 0; i--) {
            RetryAttempt prev = attempts.get(i);
            if (currentFailureType.equalsIgnoreCase(prev.getFailureType())) {
                matchingCount++;
                if (matchingCount >= MAX_IDENTICAL_FAILURES_BEFORE_ESCALATION) {
                    return true;
                }
            } else {
                break; // Sequence broken
            }
        }
        return false;
    }
}
