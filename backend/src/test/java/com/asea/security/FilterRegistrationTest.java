package com.asea.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.servlet.FilterRegistrationBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DisplayName("Security Filter Registration Regression Tests")
class FilterRegistrationTest {

    @Test
    @DisplayName("apiKeyFilterRegistration disables servlet container auto-registration to prevent duplicate filter execution")
    void apiKeyFilterRegistration_DisabledForServletContainer() {
        ApiKeyAuthenticationFilter mockFilter = mock(ApiKeyAuthenticationFilter.class);
        SecurityConfig config = new SecurityConfig();

        FilterRegistrationBean<ApiKeyAuthenticationFilter> registration = config.apiKeyFilterRegistration(mockFilter);

        assertThat(registration).isNotNull();
        assertThat(registration.isEnabled())
                .as("Filter auto-registration must be disabled so ApiKeyAuthenticationFilter only executes inside SecurityFilterChain")
                .isFalse();
        assertThat(registration.getFilter()).isSameAs(mockFilter);
    }
}
