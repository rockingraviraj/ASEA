package com.asea.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * Servlet filter that authenticates requests using an {@code X-ASEA-API-Key} header.
 *
 * <p>Uses constant-time comparison via {@link MessageDigest#isEqual(byte[], byte[])}
 * to prevent timing side-channel attacks.</p>
 *
 * <p>Binds verified server-side identity {@link AseaPrincipal} into the security context
 * including the verified tenant ID.</p>
 */
@Component
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthenticationFilter.class);
    public static final String API_KEY_HEADER = "X-ASEA-API-Key";

    private final String expectedApiKey;
    private final String defaultTenantId;

    public ApiKeyAuthenticationFilter(
            @Value("${asea.security.api-key:}") String expectedApiKey,
            @Value("${asea.security.tenant-id:default-tenant}") String defaultTenantId) {
        if (!StringUtils.hasText(expectedApiKey)) {
            throw new IllegalStateException(
                "asea.security.api-key must be configured and non-blank. " +
                "Set the API_KEY environment variable in production.");
        }
        this.expectedApiKey = expectedApiKey;
        this.defaultTenantId = defaultTenantId;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain)
            throws ServletException, IOException {

        String providedKey = request.getHeader(API_KEY_HEADER);

        if (StringUtils.hasText(providedKey) && isKeyValid(providedKey)) {
            // Authenticate — set security context with AseaPrincipal and ROLE_API authority
            AseaPrincipal principal = new AseaPrincipal("ASEA_SERVICE", defaultTenantId);
            var auth = new UsernamePasswordAuthenticationToken(
                    principal,
                    null,
                    List.of(new SimpleGrantedAuthority("ROLE_API")));
            SecurityContextHolder.getContext().setAuthentication(auth);
            // Never log key value
            log.debug("API key authentication successful for request: {} {}",
                    request.getMethod(), request.getRequestURI());
        } else if (StringUtils.hasText(providedKey)) {
            // Key provided but wrong — log at warn without revealing the key
            log.warn("Invalid API key provided for request: {} {}",
                    request.getMethod(), request.getRequestURI());
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Constant-time comparison using MessageDigest.isEqual.
     */
    private boolean isKeyValid(String providedKey) {
        if (providedKey == null) {
            return false;
        }
        byte[] a = providedKey.getBytes(StandardCharsets.UTF_8);
        byte[] b = expectedApiKey.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(a, b);
    }
}
