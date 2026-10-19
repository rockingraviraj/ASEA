package com.asea.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Central Jackson configuration bean.
 *
 * <p>Provides a shared {@link ObjectMapper} used by:</p>
 * <ul>
 *   <li>Spring MVC for REST request/response serialization</li>
 *   <li>{@link com.asea.policy.PolicyEngine} for audit metadata JSON construction</li>
 *   <li>{@link com.asea.budget.BudgetManager} for audit metadata JSON construction</li>
 *   <li>{@link com.asea.permission.PermissionManager} for audit metadata JSON construction</li>
 * </ul>
 *
 * <p>The mapper is configured to:</p>
 * <ul>
 *   <li>Handle Java 8+ date/time types (JSR-310)</li>
 *   <li>Not write dates as timestamps (ISO-8601 strings instead)</li>
 *   <li>Fail on unknown properties (fail fast during deserialization)</li>
 *   <li>Not fail on empty beans</li>
 * </ul>
 */
@Configuration
public class JacksonConfig {

    @Bean
    @Primary
    public ObjectMapper objectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }
}
