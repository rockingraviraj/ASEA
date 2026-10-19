package com.asea.permission;

/**
 * Well-known agent type identifiers used in permission checks.
 *
 * <p>These constants prevent typo bugs when checking permissions.
 * Division B (AI Engine) must use the same strings when registering agents.</p>
 */
public final class AgentTypes {

    private AgentTypes() {
    }

    public static final String ISSUE_ANALYSIS_AGENT = "ISSUE_ANALYSIS_AGENT";
    public static final String ROOT_CAUSE_AGENT = "ROOT_CAUSE_AGENT";
    public static final String PLANNING_AGENT = "PLANNING_AGENT";
    public static final String CODING_AGENT = "CODING_AGENT";
    public static final String TEST_AGENT = "TEST_AGENT";
    public static final String REVIEW_AGENT = "REVIEW_AGENT";
    public static final String SECURITY_AGENT = "SECURITY_AGENT";
    public static final String CI_AGENT = "CI_AGENT";
    public static final String PR_AGENT = "PR_AGENT";
    public static final String DEPENDENCY_AGENT = "DEPENDENCY_AGENT";
}
