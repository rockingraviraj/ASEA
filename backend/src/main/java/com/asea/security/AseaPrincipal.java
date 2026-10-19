package com.asea.security;

import java.io.Serializable;
import java.security.Principal;

/**
 * Server-side authenticated principal carrying verified identity and tenant ownership.
 */
public record AseaPrincipal(
        String name,
        String tenantId
) implements Principal, Serializable {

    @Override
    public String getName() {
        return name;
    }
}
