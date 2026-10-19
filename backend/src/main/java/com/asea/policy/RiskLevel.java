package com.asea.policy;

/**
 * Risk level classification for policy decisions.
 *
 * <p>Risk is evidence-based, never just an LLM-generated percentage.
 * The output is always one of these four levels with explainable factors.</p>
 */
public enum RiskLevel {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}
