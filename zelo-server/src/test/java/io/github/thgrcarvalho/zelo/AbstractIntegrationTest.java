package io.github.thgrcarvalho.zelo;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;

/**
 * Shared Postgres container for integration tests (real Postgres, no H2 — the
 * schema uses native enums and jsonb). The container only starts for classes
 * annotated {@link IntegrationTest}, which carries both the Testcontainers
 * extension and the {@code -DrunIntegrationTests=true} gate; the extension finds
 * this inherited static {@code @Container} field by walking the class hierarchy.
 */
public abstract class AbstractIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
