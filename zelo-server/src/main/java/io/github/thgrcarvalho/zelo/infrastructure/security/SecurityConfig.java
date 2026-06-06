package io.github.thgrcarvalho.zelo.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.thgrcarvalho.zelo.domain.apikey.ApiKeyRepository;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Registers the API-key auth filter for {@code /v1/*} only. The actuator
 * endpoints stay public; no Spring Security on the classpath (v1 auth is just a
 * static API key, per scope).
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
}
