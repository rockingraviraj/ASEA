package com.asea.common.exceptions;

public class AseaException extends RuntimeException {

    private final String code;

    public AseaException(String code, String message) {
        super(message);
        this.code = code;
    }

    public AseaException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
