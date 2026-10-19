package com.asea.common;

import com.asea.common.exceptions.AseaException;

import java.util.Optional;

public final class TenantContext {

    private static final ThreadLocal<String> CURRENT_TENANT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(String tenantId) {
        String normalized = tenantId == null ? "" : tenantId.trim();
        if (normalized.isEmpty()) {
            throw new AseaException("TENANT_REQUIRED", "tenant_id is required");
        }
        CURRENT_TENANT.set(normalized);
    }

    public static Optional<String> currentTenant() {
        String tenantId = CURRENT_TENANT.get();
        return tenantId == null || tenantId.trim().isEmpty()
                ? Optional.empty()
                : Optional.of(tenantId.trim());
    }

    public static void clear() {
        CURRENT_TENANT.remove();
    }
}
