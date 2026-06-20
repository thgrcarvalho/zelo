package io.github.thgrcarvalho.zelo.infrastructure.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The published OpenAPI contract for Zelo's public {@code /v1} integrator API
 * (springdoc auto-generates the operations + schemas from the controllers and their
 * Bean Validation). Swagger UI at {@code /swagger-ui.html}, the raw spec at
 * {@code /v3/api-docs}. {@code springdoc.paths-to-match=/v1/**} (application.yml)
 * keeps the internal {@code /account} and {@code /admin} surfaces out of the public doc.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI zeloOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Zelo API")
                .version("v1")
                .description("""
                        Developer-first LGPD compliance: subjects, purposes, consents, \
                        data-subject deletion requests (DSR), and a tamper-evident, \
                        hash-chained audit trail. Authenticate with your API key in the \
                        Authorization header. Zelo is a control plane that stores zero \
                        end-user PII — the personal data stays in your database.""")
                .contact(new Contact().name("Zelo").url("https://zelocompliance.com")
                        .email("security@zelocompliance.com"))
                .license(new License().name("Apache-2.0")
                        .url("https://www.apache.org/licenses/LICENSE-2.0")));
    }
}
