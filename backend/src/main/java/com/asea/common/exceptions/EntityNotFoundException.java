package com.asea.common.exceptions;

public class EntityNotFoundException extends AseaException {

    public EntityNotFoundException(String message) {
        super("ENTITY_NOT_FOUND", message);
    }
}
