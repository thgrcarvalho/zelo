package io.github.thgrcarvalho.zelo.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.thgrcarvalho.zelo.domain.account.AccountRepository;
import io.github.thgrcarvalho.zelo.domain.apikey.ApiKeyRepository;
import io.github.thgrcarvalho.zelo.domain.crypto.SessionTokens;
import io.github.thgrcarvalho.zelo.infrastructure.config.ZeloProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Registers the servlet auth filters for three disjoint, URL-scoped surfaces:
 * client traffic on {@code /v1/*} (DB-backed API key), runtime provisioning on
 * {@code /admin/*} (a static master key), and the self-service dashboard API on
 * {@code /account/*} (a signed session cookie). Everything else (actuator) stays
 * public. No Spring Security on the classpath — each surface authenticates with a
 * small, purpose-built filter. Guarding is by URL pattern: any new non-public path
 * MUST be added to a guarded pattern here — nothing is auth'd by default.
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
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 5);
        return registration;
    }

    /**
     * Signed-session-token helper for the {@code /account} surface. A blank
     * {@code zelo.auth.session-secret} leaves it unconfigured, so session auth
     * fails closed (no one can authenticate to the dashboard).
     */
    @Bean
    public SessionTokens sessionTokens(ZeloProperties properties) {
        return new SessionTokens(properties.getAuth().getSessionSecret());
    }

    @Bean
    public FilterRegistrationBean<SessionAuthFilter> sessionAuthFilter(SessionTokens sessionTokens,
                                                                       AccountRepository accounts) {
        FilterRegistrationBean<SessionAuthFilter> registration =
                new FilterRegistrationBean<>(new SessionAuthFilter(sessionTokens, accounts));
        registration.addUrlPatterns("/account/*");
        // Distinct order from /admin (+5) and /v1 (+10); the patterns are disjoint.
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 7);
        return registration;
    }
}
