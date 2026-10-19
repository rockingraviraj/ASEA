package com.asea.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security configuration for the ASEA Control Plane.
 *
 * <p><b>MVP Authentication Strategy:</b> API-key header authentication via {@link ApiKeyAuthenticationFilter}.
 * The caller must supply {@code X-ASEA-API-Key: <configured-value>} on every request
 * to the protected {@code /api/**} namespace.</p>
 *
 * <p><b>Tenant Isolation Model:</b>
 * The current MVP uses a server-configured API key mapped to a server-configured tenant identity
 * ({@link AseaPrincipal}). The server-side {@link TenantResolver} is the authoritative root of trust
 * for all tenant ownership and scoping. Client-supplied tenant assertions (in request bodies or query
 * parameters) are strictly assertions; if they mismatch the authenticated tenant, requests are rejected
 * with HTTP 403 Forbidden. Client input never determines or overrides ownership.</p>
 *
 * <p><b>Filter Lifecycle:</b>
 * {@link ApiKeyAuthenticationFilter} is registered explicitly within the Spring Security filter chain
 * before {@link UsernamePasswordAuthenticationFilter}. Servlet container auto-registration is disabled
 * via {@link #apiKeyFilterRegistration(ApiKeyAuthenticationFilter)} to prevent duplicate filter execution.</p>
 *
 * <p>Never log or return the API key value in any response or log output.</p>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    private final ApiKeyAuthenticationFilter apiKeyFilter;

    public SecurityConfig(ApiKeyAuthenticationFilter apiKeyFilter) {
        this.apiKeyFilter = apiKeyFilter;
    }

    /**
     * Disables servlet-container auto-registration of {@link ApiKeyAuthenticationFilter}.
     * This prevents the filter from executing twice (once in the container chain and once in Spring Security).
     */
    @Bean
    public FilterRegistrationBean<ApiKeyAuthenticationFilter> apiKeyFilterRegistration(ApiKeyAuthenticationFilter filter) {
        FilterRegistrationBean<ApiKeyAuthenticationFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // Disable CSRF — this is a stateless REST API with no browser sessions
            .csrf(AbstractHttpConfigurer::disable)

            // Stateless session — no HTTP sessions created or used
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

            // Return 401 (not 302 redirect) for unauthenticated API access
            .exceptionHandling(eh -> eh
                .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))

            // Endpoint authorization rules
            .authorizeHttpRequests(auth -> auth
                // Public: Swagger UI and OpenAPI spec
                .requestMatchers(
                    "/swagger-ui/**",
                    "/swagger-ui.html",
                    "/v3/api-docs/**",
                    "/v3/api-docs"
                ).permitAll()
                // Public: Actuator health and readiness probes (used by container orchestrators)
                .requestMatchers(
                    "/actuator/health",
                    "/actuator/health/**",
                    "/actuator/info"
                ).permitAll()
                // Everything else requires a valid API key
                .anyRequest().authenticated()
            )

            // Insert the API-key filter before the standard username/password filter
            .addFilterBefore(apiKeyFilter, UsernamePasswordAuthenticationFilter.class);

        log.info("ASEA SecurityFilterChain configured: API-key authentication, stateless sessions");
        return http.build();
    }
}
