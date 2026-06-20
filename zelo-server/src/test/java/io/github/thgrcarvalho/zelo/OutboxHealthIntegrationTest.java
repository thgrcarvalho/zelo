package io.github.thgrcarvalho.zelo;

import io.github.thgrcarvalho.zelo.infrastructure.scheduling.OutboxHealthJob;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The outbox health job surfaces dead-lettered (FAILED) webhook events — events the
 * library parks after max-attempts and never retries — via a WARN log and the live
 * {@code zelo.outbox.failed} gauge that external monitoring can alert on.
 */
@IntegrationTest
class OutboxHealthIntegrationTest extends AbstractIntegrationTest {

    @Autowired OutboxHealthJob job;
    @Autowired JdbcTemplate jdbc;
    @Autowired MeterRegistry registry;

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE outbox_event RESTART IDENTITY CASCADE");
    }

    @Test
    void gaugeReflectsDeadLetteredEvents() {
        job.run();
        assertThat(failedGauge()).isZero();

        // One dead-lettered event + one healthy PENDING (which must NOT be counted).
        jdbc.update("INSERT INTO outbox_event (event_type, payload, status, attempts, last_error) "
                + "VALUES ('dsr.delete.requested', '{}', 'FAILED', 5, 'endpoint down')");
        jdbc.update("INSERT INTO outbox_event (event_type, payload, status, attempts) "
                + "VALUES ('dsr.delete.requested', '{}', 'PENDING', 0)");

        job.run();
        assertThat(failedGauge()).isEqualTo(1.0);
    }

    private double failedGauge() {
        return registry.get("zelo.outbox.failed").gauge().value();
    }
}
