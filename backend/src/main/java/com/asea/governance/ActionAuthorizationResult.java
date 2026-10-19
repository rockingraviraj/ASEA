package com.asea.governance;

import com.asea.policy.RiskLevel;

/**
 * Result of an action authorization check composed across Policy, Permission, Budget, and Approvals.
 *
 * @param authorized whether the action is permitted to execute
 * @param reason     justification or explanation
 * @param riskLevel  evaluated risk level
 * @param approvalId associated approval ID if approval was required or used
 */
public record ActionAuthorizationResult(
        boolean authorized,
        String reason,
        RiskLevel riskLevel,
        String approvalId) {

    public static ActionAuthorizationResult authorized(RiskLevel riskLevel, String approvalId) {
        return new ActionAuthorizationResult(true, "Action authorized by policy, permission, budget, and approval checks", riskLevel, approvalId);
    }

    public static ActionAuthorizationResult denied(String reason, RiskLevel riskLevel) {
        return new ActionAuthorizationResult(false, reason, riskLevel, null);
    }
}
