package io.github.thgrcarvalho.zelo.infrastructure.scheduling;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Surfaces stuck webhook deliveries to the operator. The outbox library parks an
 * event as {@code FAILED} after {@code outbox.max-attempts} and never retries it,
 * and has no backoff or dead-letter alerting of its own — so a silently failing
 * integrator endpoint otherwise shows up only as OVERDUE requests nobody is watching.
 *
 * <p>This job WARNs whenever any event is dead-lettered (FAILED) or piling up retries,
 * and publishes a live gauge {@code zelo.outbox.failed} (at {@code /actuator/metrics})
 * so external monitoring can alert on the backlog. Single-instance and best-effort like
 * the other scheduled jobs: a DB blip is logged, not propagated.
 *
 * <p>Note: the dogfooded outbox starter (0.2.x) retries at a fixed poll interval with no
 * exponential backoff; true backoff (a {@code next_attempt_at} column) is a library-level
 * change tracked separately. This job is the operator-visibility half of that gap.
 */
@Component
public class OutboxHealthJob {

    private static final Logger log = LoggerFactory.getLogger(OutboxHealthJob.class);

    private final JdbcTemplate jdbc;
    private final int warnAttempts;
    private final AtomicLong failedGauge;

    public OutboxHealthJob(JdbcTemplate jdbc, MeterRegistry registry,
                           @Value("${zelo.outbox.warn-attempts:3}") int warnAttempts) {
        this.jdbc = jdbc;
        this.warnAttempts = warnAttempts;
        // Live gauge reflecting the current dead-letter backlog; scraped at /actuator/metrics.
        this.failedGauge = new AtomicLong(0);
        registry.gauge("zelo.outbox.failed", this.failedGauge);
    }

    // Small initial delay so the gauge reflects a real backlog promptly after a restart
    // (not blind for a full interval), then steady at the configured interval.
    @Scheduled(
            initialDelayString = "${zelo.outbox.health-initial-delay-ms:10000}",
            fixedDelayString = "${zelo.outbox.health-interval-ms:300000}")
    public void run() {
        try {
            Long failed = jdbc.queryForObject(
                    "SELECT count(*) FROM outbox_event WHERE status = 'FAILED'", Long.class);
            failedGauge.set(failed == null ? 0L : failed);
            if (failed != null && failed > 0) {
                log.warn("Outbox: {} event(s) dead-lettered (FAILED) — webhooks undelivered after retries; "
                        + "inspect last_error in outbox_event and check the integrator endpoint", failed);
            }
            Long stuck = jdbc.queryForObject(
                    "SELECT count(*) FROM outbox_event WHERE status = 'PENDING' AND attempts >= ?",
                    Long.class, warnAttempts);
            if (stuck != null && stuck > 0) {
                log.warn("Outbox: {} event(s) retrying with >= {} attempts — an integrator endpoint may be failing",
                        stuck, warnAttempts);
            }
        } catch (RuntimeException e) {
            log.warn("Outbox health check failed; will retry next cycle", e);
        }
    }
}
