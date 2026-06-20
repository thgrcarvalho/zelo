package io.github.thgrcarvalho.zelo.infrastructure.web;

import io.github.thgrcarvalho.zelo.infrastructure.security.AccountPrincipalArgumentResolver;
import io.github.thgrcarvalho.zelo.infrastructure.security.ApiKeyAuthFilter;
import io.github.thgrcarvalho.zelo.infrastructure.security.CurrentApiKeyArgumentResolver;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new CurrentApiKeyArgumentResolver());
        resolvers.add(new AccountPrincipalArgumentResolver());
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // Let ONLY the landing origin read ONLY the public demo proof endpoint
        // (its cross-origin GET from zelocompliance.com → api.zelocompliance.com).
        // No credentials are involved — this endpoint is anonymous and read-only.
        registry.addMapping(ApiKeyAuthFilter.PUBLIC_DEMO_VERIFY_PATH)
                .allowedOrigins("https://zelocompliance.com", "https://www.zelocompliance.com")
                .allowedMethods("GET")
                .allowedHeaders("Accept")
                .maxAge(3600);
    }
}
