package io.github.thgrcarvalho.zelo.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.thgrcarvalho.zelo.domain.apikey.ApiKeyRepository;
import io.github.thgrcarvalho.zelo.infrastructure.config.ZeloProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Registers the servlet auth filters. Client traffic on {@code /v1/*} is guarded
 * by the DB-backed API key; the runtime provisioning API on {@code /admin/*} is
 * guarded by a separate static master key. Everything else (actuator) stays
 * public. No Spring Security on the classpath (v1 auth is just a static API key,
 * per scope).
 */
@Configuration
public class SecurityConfig {

    @Bean
    public FilterRegistrationBean<ApiKeyAuthFilter> apiKeyAuthFilter(ApiKeyRepository apiKeys,
                                                                     ObjectMapper objectMapper) {
        FilterRegistrationBean<ApiKeyAuthFilter> registration =
                new FilterRegistrationBean<>(new ApiKeyAuthFilter(apiKeys, objectMapper));
        registration.addUrlPatterns("/v1/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }

    @Bean
    public FilterRegistrationBean<AdminAuthFilter> adminAuthFilter(ZeloProperties properties,
                                                                   ObjectMapper objectMapper) {
        FilterRegistrationBean<AdminAuthFilter> registration =
                new FilterRegistrationBean<>(
                        new AdminAuthFilter(properties.getAdmin().getMasterKey(), objectMapper));
        registration.addUrlPatterns("/admin/*");
        // Distinct, higher precedence than the /v1 filter so ordering is deterministic.
        // Guarding is by URL pattern: any new non-public path MUST be added to a
        // guarded pattern here — nothing is auth'd by default.
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 5);
        return registration;
    }
}
