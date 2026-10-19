package com.asea.common.exceptions;

public class ApprovalRequiredException extends AseaException {

    public ApprovalRequiredException(String message) {
        super("HUMAN_APPROVAL_REQUIRED", message);
    }
}
