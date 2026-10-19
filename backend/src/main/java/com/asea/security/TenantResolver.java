package com.asea.security;

import com.asea.common.exceptions.PermissionDeniedException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Resolves the authenticated tenant identity server-side.
 *
 * <p><b>Root of trust:</b> the only legitimate source of tenant identity in the MVP is the
 * {@link AseaPrincipal} created by {@link ApiKeyAuthenticationFilter} after successfully
 * validating the configured API key. There is no fallback to any other source.</p>
 *
 * <p><b>Fail-closed contract:</b></p>
 * <ul>
 *   <li>Absent authentication → {@link PermissionDeniedException}</li>
 *   <li>Unauthenticated authentication → {@link PermissionDeniedException}</li>
 *   <li>Anonymous authentication → {@link PermissionDeniedException}</li>
 *   <li>Principal that is not {@link AseaPrincipal} → {@link PermissionDeniedException}</li>
 *   <li>Null or blank tenant ID on the principal → {@link PermissionDeniedException}</li>
 *   <li>No fallback to {@code defaultTenantId} for an unknown principal type</li>
 * </ul>
 *
 * <p><b>Assertion validation:</b> if a caller supplies an optional tenant assertion (e.g.,
 * a legacy query parameter), {@link #validateAndResolveTenant(String)} verifies it matches
 * the resolved tenant. A mismatch throws {@link PermissionDeniedException} (→ HTTP 403).</p>
 *
 * <p>The {@link #getDefaultTenantId()} accessor is available for internal infrastructure
 * use (e.g., {@link ApiKeyAuthenticationFilter}) when constructing a verified principal.
 * It must not be used as an authorization fallback for unknown principals.</p>
 */
@Component
public class TenantResolver {

    private final String defaultTenantId;

    public TenantResolver(@Value("${asea.security.tenant-id:default-tenant}") String defaultTenantId) {
        this.defaultTenantId = defaultTenantId;
    }

    /**
     * Resolves the verified tenant ID for the current authenticated context.
     *
     * <p>Only accepts {@link AseaPrincipal} instances created by the API-key filter.
     * All other authentication states (absent, unauthenticated, anonymous, wrong type,
     * blank tenant) are rejected.</p>
     *
     * @return the verified tenant ID from the authenticated principal
     * @throws PermissionDeniedException for any authentication or principal problem
     */
    public String resolveRequiredTenantId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        if (auth == null) {
            throw new PermissionDeniedException("Unauthenticated request: no security context");
        }
        if (!auth.isAuthenticated()) {
            throw new PermissionDeniedException("Unauthenticated request: authentication is not confirmed");
        }
        if (auth instanceof AnonymousAuthenticationToken) {
            throw new PermissionDeniedException("Anonymous access is not permitted");
        }
        if (!(auth.getPrincipal() instanceof AseaPrincipal principal)) {
            // Unknown principal type — fail closed; do NOT fall back to defaultTenantId
            throw new PermissionDeniedException(
                    "Unauthenticated request: unrecognised principal type");
        }
        String tenantId = principal.tenantId();
        if (!StringUtils.hasText(tenantId)) {
            throw new PermissionDeniedException(
                    "Authenticated principal has no valid tenant identifier");
        }
        return tenantId;
    }

    /**
     * Validates an optional caller-supplied tenant assertion against the authenticated tenant.
     *
     * <p>This method is used when a caller supplies a {@code tenantId} query parameter or
     * similar optional assertion. The parameter is treated as an assertion only — it never
     * overrides the authenticated tenant. A mismatch triggers a 403.</p>
     *
     * @param assertedTenantId the caller-supplied tenant assertion (may be null or blank)
     * @return the verified server-side tenant ID
     * @throws PermissionDeniedException if the assertion does not match the authenticated tenant
     */
    public String validateAndResolveTenant(String assertedTenantId) {
        String verifiedTenant = resolveRequiredTenantId();
        if (StringUtils.hasText(assertedTenantId) && !assertedTenantId.equals(verifiedTenant)) {
            // Log without disclosing what the owning tenant is
            throw new PermissionDeniedException(
                    "Cross-tenant access denied: supplied tenantId does not match authenticated identity");
        }
        return verifiedTenant;
    }

    /**
     * Returns the configured default tenant ID.
     *
     * <p>This accessor is intended for use by {@link ApiKeyAuthenticationFilter} only,
     * when constructing a verified {@link AseaPrincipal} after the API key passes validation.
     * It must NOT be used as an authorization fallback for requests with unknown principals.</p>
     *
     * @return the configured default tenant ID
     */
    public String getDefaultTenantId() {
        return defaultTenantId;
    }
}
