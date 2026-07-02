package io.github.thgrcarvalho.zelo.infrastructure.scheduling;

import io.github.thgrcarvalho.zelo.application.UsageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.YearMonth;
import java.time.ZoneOffset;

/**
 * Nightly upsert of {@code usage_rollups} for every account, covering the last
 * {@link #BACKFILL_MONTHS} finished months. The windows are finished and their
 * sources are append-only, so the rollup is idempotent — re-rolling settled
 * months is a no-op rewrite, and the multi-month sweep means even weeks of
 * downtime can't permanently lose a month. Single-instance deployment, so a
 * plain {@code @Scheduled} is enough (same caveat as the other jobs for
 * scale-out).
 *
 * <p>Best-effort like its neighbours: failures are caught and logged so they
 * can't poison the shared scheduler; the next night retries.</p>
 */
@Component
public class UsageRollupJob {

    private static final Logger log = LoggerFactory.getLogger(UsageRollupJob.class);

    // How many finished months each nightly run re-rolls. Bounds the downtime a
    // deployment can absorb before a month's rollup would need a manual
    // UsageService.rollUpMonth() backfill.
    private static final int BACKFILL_MONTHS = 3;

    private final UsageService usage;

    public UsageRollupJob(UsageService usage) {
        this.usage = usage;
    }

    @Scheduled(cron = "${zelo.usage.rollup-cron:0 40 3 * * *}", zone = "UTC")
    public void run() {
        YearMonth current = YearMonth.now(ZoneOffset.UTC);
        for (int i = 1; i <= BACKFILL_MONTHS; i++) {
            YearMonth month = current.minusMonths(i);
            try {
                usage.rollUpMonth(month);
            } catch (RuntimeException e) {
                log.warn("Usage rollup for {} failed; will retry next night", month, e);
            }
        }
    }
}
