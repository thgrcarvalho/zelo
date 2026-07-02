package io.github.thgrcarvalho.zelo.application;

import io.github.thgrcarvalho.zelo.infrastructure.persistence.JdbcUsageStats;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Account-facing usage figures — the future billing meter. The current month is
 * always computed live; finished months are served from {@code usage_rollups},
 * maintained idempotently by the nightly {@code UsageRollupJob}.
 */
@Service
public class UsageService {

    public record MonthlyUsage(
            String month, long subjects, long consentEvents, long auditEvents, long dsrRequests) {
    }

    private final JdbcUsageStats stats;

    public UsageService(JdbcUsageStats stats) {
        this.stats = stats;
    }

    public MonthlyUsage currentMonth(UUID accountId) {
        YearMonth now = YearMonth.now(ZoneOffset.UTC);
        JdbcUsageStats.MonthCounts c = stats.countWindow(accountId, startOf(now), startOf(now.plusMonths(1)));
        return new MonthlyUsage(now.toString(), c.subjects(), c.consentEvents(), c.auditEvents(), c.dsrRequests());
    }

    /** Stored (finished) months, newest first. */
    public List<MonthlyUsage> history(UUID accountId, int months) {
        return stats.history(accountId, months).stream()
                .map(r -> new MonthlyUsage(YearMonth.from(r.month()).toString(),
                        r.subjects(), r.consentEvents(), r.auditEvents(), r.dsrRequests()))
                .toList();
    }

    /** Rolls up the given month for every account. Idempotent — safe to re-run. */
    @Transactional
    public void rollUpMonth(YearMonth month) {
        stats.rollUpAllAccounts(month.atDay(1), startOf(month), startOf(month.plusMonths(1)));
    }

    private static Instant startOf(YearMonth month) {
        return month.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }
}
