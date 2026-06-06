package io.github.thgrcarvalho.zelo;

import org.junit.jupiter.api.Test;

/**
 * Smoke test: the Spring context boots, Flyway migrates a real Postgres, and
 * Hibernate validates the entity mappings against the migrated schema.
 */
@IntegrationTest
class ZeloServerApplicationTests extends AbstractIntegrationTest {

    @Test
    void contextLoads() {
        // The context loading (with Flyway + ddl-auto=validate) is the assertion.
    }
}
