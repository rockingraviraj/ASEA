package com.asea.common.exceptions;

public class RetryLimitExceededException extends AseaException {

    public RetryLimitExceededException(String message) {
        super("RETRY_LIMIT_EXCEEDED", message);
    }
}
