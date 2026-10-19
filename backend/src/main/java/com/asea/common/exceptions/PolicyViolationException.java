package com.asea.common.exceptions;

public class PolicyViolationException extends AseaException {

    public PolicyViolationException(String message) {
        super("POLICY_VIOLATION", message);
    }
}
