package com.asea.common.exceptions;

public class PermissionDeniedException extends AseaException {

    public PermissionDeniedException(String message) {
        super("PERMISSION_DENIED", message);
    }
}
