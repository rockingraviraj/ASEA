package com.asea.policy;

import java.util.List;

/**
 * Result of a policy evaluation.
 *
 * <p>Includes the decision, risk level, and the factors that contributed to
 * the risk assessment. Risk is always evidence-based — never a bare LLM score.</p>
 *
 * @param allowed             whether the action is permitted by policy
 * @param requiresApproval    whether human approval is required before proceeding
 * @param riskLevel           evidence-based risk classification
 * @param contributingFactors human-readable list of factors that drove the decision
 * @param reason              summary reason for the decision
 */
public record PolicyDecision(
        boolean allowed,
        boolean requiresApproval,
        RiskLevel riskLevel,
        List<String> contributingFactors,
        String reason) {

    public static PolicyDecision allow(RiskLevel riskLevel, List<String> factors) {
        return new PolicyDecision(true, false, riskLevel, factors, "Action permitted by policy");
    }

    public static PolicyDecision allowWithApproval(RiskLevel riskLevel, List<String> factors, String reason) {
        return new PolicyDecision(true, true, riskLevel, factors, reason);
    }

    public static PolicyDecision deny(RiskLevel riskLevel, List<String> factors, String reason) {
        return new PolicyDecision(false, false, riskLevel, factors, reason);
    }
}
