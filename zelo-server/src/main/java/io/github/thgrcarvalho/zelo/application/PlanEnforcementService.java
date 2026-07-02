package io.github.thgrcarvalho.zelo.application;

import io.github.thgrcarvalho.zelo.application.error.ConflictException;
import io.github.thgrcarvalho.zelo.application.error.TooManyRequestsException;
import io.github.thgrcarvalho.zelo.domain.account.Plan;
import io.github.thgrcarvalho.zelo.infrastructure.config.ZeloProperties;
import io.github.thgrcarvalho.zelo.infrastructure.persistence.JdbcUsageStats;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Free-tier gate for the billing MVP. Deliberately soft: compliance writes are
 * refused (an explicit 429, never a silent drop) only past ceiling ×
 * {@code zelo.plans.hard-cap-multiplier}; between the ceiling and the hard cap
 * the write succeeds and the hourly {@code UsageAlertJob} emails the account.
 * DSR (erasure) writes are NEVER quota-gated — refusing a legal-obligation
 * trigger over quota would be wrong, so no check exists on that path by design.
 *
 * <p>PRO accounts and keys without an account (operator/bootstrap/internal) are
 * unlimited. Counts are queried live per write — at current volume that is two
 * indexed scalar subqueries; add caching only when traffic earns it.</p>
 */
@Service
public class PlanEnforcementService {

    /** Which metered write is being attempted. */
    public enum WriteKind {
        /** New subject rows meter against {@code subjects-per-month}. */
        SUBJECT,
        /** Consent events meter volume via {@code audit-events-per-month}. */
        CONSENT
    }

    private final JdbcUsageStats stats;
    private final ZeloProperties properties;

    public PlanEnforcementService(JdbcUsageStats stats, ZeloProperties properties) {
        this.stats = stats;
        this.properties = properties;
    }

    /** Gate a metered /v1 write for the authenticated key. */
    public void checkV1Write(UUID apiKeyId, WriteKind kind) {
        JdbcUsageStats.KeyPlan keyPlan = stats.planForKey(apiKeyId);
        if (unmetered(keyPlan)) {
            return;
        }
        JdbcUsageStats.MonthCounts counts = currentMonthCounts(keyPlan.accountId());
        enforceHardCap(counts, kind);
    }

    /**
     * Gate a consent write. WITHDRAW is a data-subject right and is never gated.
     * Other actions meter volume via the audit-events ceiling; when the consent
     * targets a subject the key has never seen (recording it would mint a new
     * subject row), the subjects ceiling applies too — otherwise the consent
     * path would be a side door around the subjects hard cap.
     */
    public void checkConsentWrite(UUID apiKeyId, String externalId, boolean withdraw) {
        if (withdraw) {
            return;
        }
        JdbcUsageStats.KeyPlan keyPlan = stats.planForKey(apiKeyId);
        if (unmetered(keyPlan)) {
            return;
        }
        JdbcUsageStats.MonthCounts counts = currentMonthCounts(keyPlan.accountId());
        enforceHardCap(counts, WriteKind.CONSENT);
        if (!stats.subjectExists(apiKeyId, externalId)) {
            enforceHardCap(counts, WriteKind.SUBJECT);
        }
    }

    /** Gate self-service key creation: FREE accounts hold a bounded number of active keys. */
    public void checkKeyCreation(UUID accountId, Plan plan) {
        if (plan != Plan.FREE) {
            return;
        }
        int allowed = properties.getPlans().getFree().getApiKeys();
        if (stats.activeKeyCount(accountId) >= allowed) {
            throw new ConflictException(
                    "Free plan allows " + allowed + " active API keys — revoke one or upgrade.");
        }
    }

    private static boolean unmetered(JdbcUsageStats.KeyPlan keyPlan) {
        return keyPlan == null || keyPlan.accountId() == null || !Plan.FREE.name().equals(keyPlan.plan());
    }

    private JdbcUsageStats.MonthCounts currentMonthCounts(UUID accountId) {
        YearMonth month = YearMonth.now(ZoneOffset.UTC);
        return stats.countWindow(accountId, startOf(month), startOf(month.plusMonths(1)));
    }

    private static Instant startOf(YearMonth month) {
        return month.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private void enforceHardCap(JdbcUsageStats.MonthCounts counts, WriteKind kind) {
        ZeloProperties.Plans plans = properties.getPlans();
        long used = kind == WriteKind.SUBJECT ? counts.subjects() : counts.auditEvents();
        long ceiling = kind == WriteKind.SUBJECT
                ? plans.getFree().getSubjectsPerMonth()
                : plans.getFree().getAuditEventsPerMonth();
        long hardCap = ceiling * plans.getHardCapMultiplier();
        if (used >= hardCap) {
            String metric = kind == WriteKind.SUBJECT ? "subjects" : "audit events";
            throw new TooManyRequestsException(
                    "Free plan: " + metric + " this month (" + used + ") reached "
                            + plans.getHardCapMultiplier() + "x the plan ceiling of " + ceiling
                            + ". Upgrade the plan to keep writing.");
        }
    }
}
