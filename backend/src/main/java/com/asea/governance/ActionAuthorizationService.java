package com.asea.governance;

import com.asea.approval.Approval;
import com.asea.approval.ApprovalService;
import com.asea.budget.BudgetCheckResult;
import com.asea.budget.BudgetManager;
import com.asea.common.exceptions.ApprovalRequiredException;
import com.asea.common.exceptions.BudgetExceededException;
import com.asea.common.exceptions.PermissionDeniedException;
import com.asea.common.exceptions.PolicyViolationException;
import com.asea.permission.PermissionManager;
import com.asea.policy.PolicyDecision;
import com.asea.policy.PolicyEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Coordination and enforcement service composing Policy Engine, Permission Manager,
 * Budget Manager, and Approval Service into a unified execution gate.
 *
 * <p>Enforces the locked governance workflow for any protected engineering action:
 * <ol>
 *   <li>Policy Evaluation (answers "Is this action allowed by policy?")</li>
 *   <li>Permission Check (answers "Does this actor have grant for this operation?")</li>
 *   <li>Budget Check (answers "Does this task have remaining budget and execution time?")</li>
 *   <li>Approval Gate (if policy requires approval, execution is blocked until a valid,
 *       persisted approval is granted)</li>
 * </ol>
 * </p>
 */
@Service
public class ActionAuthorizationService {

    private static final Logger log = LoggerFactory.getLogger(ActionAuthorizationService.class);

    private final PolicyEngine policyEngine;
    private final PermissionManager permissionManager;
    private final BudgetManager budgetManager;
    private final ApprovalService approvalService;

    public ActionAuthorizationService(PolicyEngine policyEngine,
                                      PermissionManager permissionManager,
                                      BudgetManager budgetManager,
                                      ApprovalService approvalService) {
        this.policyEngine = policyEngine;
        this.permissionManager = permissionManager;
        this.budgetManager = budgetManager;
        this.approvalService = approvalService;
    }

    /**
     * Authorizes an action for execution.
     *
     * @param tenantId      authoritative tenant ID derived from trusted context
     * @param taskId        target task identifier
     * @param actorType     agent/actor type requesting the action
     * @param action        action name (e.g. "WRITE_FILE", "CREATE_PR")
     * @param filePaths     file paths touched by the action
     * @param modifications estimated lines/modifications
     * @return ActionAuthorizationResult if authorized
     * @throws PolicyViolationException   if explicitly denied by policy
     * @throws PermissionDeniedException  if actor does not have permission
     * @throws BudgetExceededException    if task budget is missing or exhausted
     * @throws ApprovalRequiredException  if human approval is required and not yet granted
     */
    @Transactional
    public ActionAuthorizationResult authorize(String tenantId,
                                              String taskId,
                                              String actorType,
                                              String action,
                                              List<String> filePaths,
                                              int modifications) {
        // Step 1: Policy Evaluation
        PolicyDecision policyDecision = policyEngine.evaluate(tenantId, action, taskId, filePaths, modifications);
        if (!policyDecision.allowed()) {
            log.warn("Action DENIED by policy: action={} taskId={} tenantId={} reason={}",
                    action, taskId, tenantId, policyDecision.reason());
            throw new PolicyViolationException("Policy violation for action: " + action
                    + ". Reason: " + policyDecision.reason());
        }

        // Step 2: Permission Check
        if (!permissionManager.isPermitted(tenantId, actorType, action, taskId)) {
            log.warn("Action DENIED by permission: actorType={} action={} taskId={} tenantId={}",
                    actorType, action, taskId, tenantId);
            throw new PermissionDeniedException(
                    actorType + " is not permitted to perform operation: " + action);
        }

        // Step 3: Budget Check
        BudgetCheckResult budgetResult = budgetManager.check(tenantId, taskId);
        if (!budgetResult.allowed()) {
            log.warn("Action DENIED by budget: taskId={} tenantId={} reason={}",
                    taskId, tenantId, budgetResult.exhaustedReason());
            throw new BudgetExceededException("Budget exhausted for task " + taskId
                    + ": " + budgetResult.exhaustedReason());
        }

        // Step 4: Approval Gate
        String approvalId = null;
        if (policyDecision.requiresApproval()) {
            // Check if an existing valid approval has been granted for this task and action
            Optional<Approval> validApproval = approvalService.findValidApproval(tenantId, taskId, action);
            if (validApproval.isEmpty()) {
                // Persist an approval request idempotently
                Approval requested = approvalService.requestApproval(
                        tenantId, taskId, action, policyDecision.riskLevel(), actorType, policyDecision.reason());
                log.info("Execution BLOCKED pending approval: approvalId={} action={} taskId={} tenantId={}",
                        requested.getApprovalId(), action, taskId, tenantId);
                throw new ApprovalRequiredException("Human approval required for action: " + action
                        + ". Approval request: " + requested.getApprovalId());
            }

            approvalId = validApproval.get().getApprovalId();
            log.info("Action authorized with granted approval: approvalId={} action={} taskId={} tenantId={}",
                    approvalId, action, taskId, tenantId);
        }

        return ActionAuthorizationResult.authorized(policyDecision.riskLevel(), approvalId);
    }
}
