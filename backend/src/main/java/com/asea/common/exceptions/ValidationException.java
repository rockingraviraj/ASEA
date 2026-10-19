package com.asea.common.exceptions;

public class ValidationException extends AseaException {

    public ValidationException(String message) {
        super("VALIDATION_ERROR", message);
    }
}
