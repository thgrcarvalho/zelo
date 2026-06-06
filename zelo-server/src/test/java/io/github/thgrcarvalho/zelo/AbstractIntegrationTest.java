package io.github.thgrcarvalho.zelo;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Shared Postgres for integration tests (real Postgres, no H2 — the schema uses
 * native enums and jsonb). A single container is started once for the whole JVM
 * and never stopped between classes, so a background poller (the outbox) always
 * has a database to talk to; Testcontainers' Ryuk reaps it at JVM exit. The
 * container only starts when {@code -DrunIntegrationTests=true}, the same flag
 * the {@link IntegrationTest} gate keys off, so the default build stays
 * Docker-free.
 */
public abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        if (Boolean.getBoolean("runIntegrationTests")) {
            POSTGRES.start();
        }
    }

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
