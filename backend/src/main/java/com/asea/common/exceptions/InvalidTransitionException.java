package com.asea.common.exceptions;

public class InvalidTransitionException extends AseaException {

    public InvalidTransitionException(String message) {
        super("INVALID_STATE_TRANSITION", message);
    }
}
