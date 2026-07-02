package io.github.thgrcarvalho.zelo.infrastructure.scheduling;

import io.github.thgrcarvalho.zelo.application.email.EmailMessage;
import io.github.thgrcarvalho.zelo.application.email.EmailSender;
import io.github.thgrcarvalho.zelo.infrastructure.config.ZeloProperties;
import io.github.thgrcarvalho.zelo.infrastructure.persistence.JdbcUsageStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Hourly usage-threshold emails for FREE accounts: one email per (account,
 * month, metric, threshold) when current-month usage crosses 80% or 100% of a
 * plan ceiling. Dedupe rides {@code usage_alerts}; the order is send-then-record
 * so a failed send leaves no row and simply retries next hour (single instance,
 * so no concurrent-send race). Crossing 100% records the 80% row too, so a fast
 * burner gets one email, not two.
 *
 * <p>Warnings are the soft half of enforcement — writes keep succeeding until
 * the hard cap in {@code PlanEnforcementService}. Best-effort like the other
 * jobs: per-account failures are logged and skipped.</p>
 */
@Component
public class UsageAlertJob {

    private static final Logger log = LoggerFactory.getLogger(UsageAlertJob.class);
    private static final int[] THRESHOLDS = {100, 80};

    private final JdbcUsageStats stats;
    private final ZeloProperties properties;
    private final EmailSender emails;

    public UsageAlertJob(JdbcUsageStats stats, ZeloProperties properties, EmailSender emails) {
        this.stats = stats;
        this.properties = properties;
        this.emails = emails;
    }

    @Scheduled(
            initialDelayString = "${zelo.plans.alert-interval-ms:3600000}",
            fixedDelayString = "${zelo.plans.alert-interval-ms:3600000}")
    public void run() {
        YearMonth month = YearMonth.now(ZoneOffset.UTC);
        Instant start = month.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant end = month.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();

        for (JdbcUsageStats.MeteredAccount account : stats.activeFreeAccounts()) {
            try {
                JdbcUsageStats.MonthCounts counts = stats.countWindow(account.id(), start, end);
                ZeloProperties.Plans.Free free = properties.getPlans().getFree();
                alertIfCrossed(account, month, "subjects", counts.subjects(), free.getSubjectsPerMonth());
                alertIfCrossed(account, month, "audit_events", counts.auditEvents(), free.getAuditEventsPerMonth());
            } catch (RuntimeException e) {
                log.warn("Usage alert check failed for account {}; skipping until next run",
                        account.id(), e);
            }
        }
    }

    private void alertIfCrossed(JdbcUsageStats.MeteredAccount account, YearMonth month,
                                String metric, long used, long ceiling) {
        long pctUsed = ceiling == 0 ? 0 : used * 100 / ceiling;
        for (int threshold : THRESHOLDS) {
            if (pctUsed < threshold) {
                continue;
            }
            if (!stats.alertAlreadySent(account.id(), month.atDay(1), metric, threshold)) {
                emails.send(usageEmail(account.email(), month, metric, used, ceiling, threshold));
                markThresholdAndBelow(account.id(), month, metric, threshold);
            }
            return; // highest crossed threshold handled; lower ones are marked, not re-sent
        }
    }

    private void markThresholdAndBelow(UUID accountId, YearMonth month, String metric, int crossed) {
        for (int threshold : THRESHOLDS) {
            if (threshold <= crossed) {
                stats.recordAlert(accountId, month.atDay(1), metric, threshold);
            }
        }
    }

    private EmailMessage usageEmail(String to, YearMonth month, String metric,
                                    long used, long ceiling, int threshold) {
        String subject = "Zelo: " + metric.replace('_', ' ') + " at " + threshold
                + "% of your free plan for " + month;
        String body = """
                Your Zelo account has used %d of the %d %s included in the free plan for %s.

                Writes keep working past the ceiling, but far enough beyond it new writes \
                are refused. To raise the limits, upgrade your plan from the dashboard at \
                https://zelocompliance.com/app/ — or reply to this email.
                """.formatted(used, ceiling, metric.replace('_', ' '), month);
        return new EmailMessage(to, subject, body);
    }
}
