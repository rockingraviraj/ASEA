package com.asea.security;

import com.asea.common.exceptions.PermissionDeniedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link TenantResolver}.
 *
 * <p>Verifies fail-closed behavior: only a valid {@link AseaPrincipal} with a non-blank
 * tenant ID may succeed. All other authentication states are rejected.</p>
 */
@DisplayName("TenantResolver")
class TenantResolverTest {

    private TenantResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new TenantResolver("default-tenant");
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // =========================================================================
    // resolveRequiredTenantId()
    // =========================================================================

    @Nested
    @DisplayName("resolveRequiredTenantId")
    class ResolveRequired {

        @Test
        @DisplayName("returns tenant ID from a valid AseaPrincipal")
        void validPrincipal_ReturnsTenantId() {
            setAuth(new AseaPrincipal("user", "tenant-a"));
            assertThat(resolver.resolveRequiredTenantId()).isEqualTo("tenant-a");
        }

        @Test
        @DisplayName("absent authentication context → PermissionDeniedException")
        void noAuthentication_ThrowsPermissionDenied() {
            // SecurityContextHolder is clear
            assertThatThrownBy(() -> resolver.resolveRequiredTenantId())
                    .isInstanceOf(PermissionDeniedException.class);
        }

        @Test
        @DisplayName("unauthenticated token → PermissionDeniedException")
        void unauthenticatedToken_ThrowsPermissionDenied() {
            var auth = new TestingAuthenticationToken("user", null);
            auth.setAuthenticated(false);
            SecurityContextHolder.getContext().setAuthentication(auth);
            assertThatThrownBy(() -> resolver.resolveRequiredTenantId())
                    .isInstanceOf(PermissionDeniedException.class);
        }

        @Test
        @DisplayName("AnonymousAuthenticationToken → PermissionDeniedException")
        void anonymousToken_ThrowsPermissionDenied() {
            var anon = new AnonymousAuthenticationToken(
                    "key", "anonymousUser",
                    List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));
            SecurityContextHolder.getContext().setAuthentication(anon);
            assertThatThrownBy(() -> resolver.resolveRequiredTenantId())
                    .isInstanceOf(PermissionDeniedException.class);
        }

        @Test
        @DisplayName("String principal (wrong type) → PermissionDeniedException — no fallback to defaultTenantId")
        void wrongPrincipalType_ThrowsPermissionDenied() {
            var auth = new UsernamePasswordAuthenticationToken(
                    "just-a-string", null,
                    List.of(new SimpleGrantedAuthority("ROLE_API")));
            SecurityContextHolder.getContext().setAuthentication(auth);
            // Must NOT fall back to "default-tenant"; must throw
            assertThatThrownBy(() -> resolver.resolveRequiredTenantId())
                    .isInstanceOf(PermissionDeniedException.class);
        }

        @Test
        @DisplayName("AseaPrincipal with null tenantId → PermissionDeniedException")
        void nullTenantOnPrincipal_ThrowsPermissionDenied() {
            setAuth(new AseaPrincipal("user", null));
            assertThatThrownBy(() -> resolver.resolveRequiredTenantId())
                    .isInstanceOf(PermissionDeniedException.class);
        }

        @Test
        @DisplayName("AseaPrincipal with blank tenantId → PermissionDeniedException")
        void blankTenantOnPrincipal_ThrowsPermissionDenied() {
            setAuth(new AseaPrincipal("user", "  "));
            assertThatThrownBy(() -> resolver.resolveRequiredTenantId())
                    .isInstanceOf(PermissionDeniedException.class);
        }

        @Test
        @DisplayName("does NOT fall back to defaultTenantId for wrong principal type")
        void noFallbackToDefaultTenantForUnknownPrincipal() {
            // Even though defaultTenantId = "default-tenant", wrong principal type must fail
            var auth = new UsernamePasswordAuthenticationToken(
                    42, null, // integer principal
                    List.of(new SimpleGrantedAuthority("ROLE_API")));
            SecurityContextHolder.getContext().setAuthentication(auth);
            assertThatThrownBy(() -> resolver.resolveRequiredTenantId())
                    .isInstanceOf(PermissionDeniedException.class)
                    .hasMessageContaining("unrecognised principal type");
        }
    }

    // =========================================================================
    // validateAndResolveTenant()
    // =========================================================================

    @Nested
    @DisplayName("validateAndResolveTenant")
    class ValidateAndResolve {

        @Test
        @DisplayName("null assertion → returns authenticated tenant (normal operation)")
        void nullAssertion_ReturnsAuthenticatedTenant() {
            setAuth(new AseaPrincipal("user", "tenant-a"));
            assertThat(resolver.validateAndResolveTenant(null)).isEqualTo("tenant-a");
        }

        @Test
        @DisplayName("blank assertion → treated as absent, returns authenticated tenant")
        void blankAssertion_ReturnsAuthenticatedTenant() {
            setAuth(new AseaPrincipal("user", "tenant-a"));
            assertThat(resolver.validateAndResolveTenant("")).isEqualTo("tenant-a");
            assertThat(resolver.validateAndResolveTenant("   ")).isEqualTo("tenant-a");
        }

        @Test
        @DisplayName("matching assertion → accepted, returns authenticated tenant")
        void matchingAssertion_ReturnsAuthenticatedTenant() {
            setAuth(new AseaPrincipal("user", "tenant-a"));
            assertThat(resolver.validateAndResolveTenant("tenant-a")).isEqualTo("tenant-a");
        }

        @Test
        @DisplayName("mismatching assertion → PermissionDeniedException (403)")
        void mismatchingAssertion_ThrowsPermissionDenied() {
            setAuth(new AseaPrincipal("user", "tenant-a"));
            assertThatThrownBy(() -> resolver.validateAndResolveTenant("tenant-b"))
                    .isInstanceOf(PermissionDeniedException.class)
                    .hasMessageContaining("Cross-tenant access denied");
        }

        @Test
        @DisplayName("spoofed assertion with different casing → PermissionDeniedException")
        void caseVariantSpoofedAssertion_ThrowsPermissionDenied() {
            setAuth(new AseaPrincipal("user", "tenant-a"));
            assertThatThrownBy(() -> resolver.validateAndResolveTenant("Tenant-A"))
                    .isInstanceOf(PermissionDeniedException.class);
        }

        @Test
        @DisplayName("absent auth + any assertion → PermissionDeniedException")
        void noAuth_WithAnyAssertion_ThrowsPermissionDenied() {
            assertThatThrownBy(() -> resolver.validateAndResolveTenant("tenant-a"))
                    .isInstanceOf(PermissionDeniedException.class);
        }
    }

    // =========================================================================
    // getDefaultTenantId()
    // =========================================================================

    @Test
    @DisplayName("getDefaultTenantId returns configured value")
    void getDefaultTenantId_ReturnsConfiguredValue() {
        assertThat(resolver.getDefaultTenantId()).isEqualTo("default-tenant");
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private void setAuth(AseaPrincipal principal) {
        var auth = new UsernamePasswordAuthenticationToken(
                principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_API")));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}
