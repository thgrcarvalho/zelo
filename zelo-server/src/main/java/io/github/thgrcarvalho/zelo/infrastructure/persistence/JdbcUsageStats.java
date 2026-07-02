package io.github.thgrcarvalho.zelo.infrastructure.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Read-side usage counters per account. An account's usage is the sum over its
 * keys ({@code api_keys.account_id}); keys without an account (operator-minted,
 * bootstrap) belong to no account and are never counted here.
 *
 * <p>Windows are half-open {@code [start, end)} in UTC. Subjects, consents and
 * DSRs count by server receipt time ({@code created_at}); audit entries by their
 * server-stamped {@code occurred_at} (the audit table has no created_at — the two
 * are assigned in the same request).</p>
 */
@Repository
public class JdbcUsageStats {

    public record MonthCounts(long subjects, long consentEvents, long auditEvents, long dsrRequests) {
    }

    public record RollupRow(
            LocalDate month, long subjects, long consentEvents, long auditEvents, long dsrRequests) {
    }

    private static final String COUNT_SQL = """
            SELECT
              (SELECT count(*) FROM subjects s
                 JOIN api_keys k ON k.id = s.api_key_id
                WHERE k.account_id = ? AND s.created_at >= ? AND s.created_at < ?) AS subjects,
              (SELECT count(*) FROM consent_events c
                 JOIN subjects s2 ON s2.id = c.subject_id
                 JOIN api_keys k2 ON k2.id = s2.api_key_id
                WHERE k2.account_id = ? AND c.created_at >= ? AND c.created_at < ?) AS consent_events,
              (SELECT count(*) FROM audit_log a
                 JOIN api_keys k3 ON k3.id = a.api_key_id
                WHERE k3.account_id = ? AND a.occurred_at >= ? AND a.occurred_at < ?) AS audit_events,
              (SELECT count(*) FROM dsr_requests d
                 JOIN api_keys k4 ON k4.id = d.api_key_id
                WHERE k4.account_id = ? AND d.created_at >= ? AND d.created_at < ?) AS dsr_requests
            """;

    // One statement rolls up ALL accounts for the window: aggregate each source
    // per account, left-join onto accounts (an idle account gets an all-zero row,
    // which keeps "no row yet" distinct from "rolled up as zero"), then upsert.
    private static final String ROLLUP_SQL = """
            INSERT INTO usage_rollups
                (account_id, month, subjects, consent_events, audit_events, dsr_requests, computed_at)
            SELECT a.id, ?,
                   COALESCE(su.c, 0), COALESCE(ce.c, 0), COALESCE(au.c, 0), COALESCE(dr.c, 0), now()
              FROM accounts a
              LEFT JOIN (SELECT k.account_id, count(*) c FROM subjects s
                           JOIN api_keys k ON k.id = s.api_key_id
                          WHERE s.created_at >= ? AND s.created_at < ? AND k.account_id IS NOT NULL
                          GROUP BY k.account_id) su ON su.account_id = a.id
              LEFT JOIN (SELECT k.account_id, count(*) c FROM consent_events ev
                           JOIN subjects s ON s.id = ev.subject_id
                           JOIN api_keys k ON k.id = s.api_key_id
                          WHERE ev.created_at >= ? AND ev.created_at < ? AND k.account_id IS NOT NULL
                          GROUP BY k.account_id) ce ON ce.account_id = a.id
              LEFT JOIN (SELECT k.account_id, count(*) c FROM audit_log al
                           JOIN api_keys k ON k.id = al.api_key_id
                          WHERE al.occurred_at >= ? AND al.occurred_at < ? AND k.account_id IS NOT NULL
                          GROUP BY k.account_id) au ON au.account_id = a.id
              LEFT JOIN (SELECT k.account_id, count(*) c FROM dsr_requests d
                           JOIN api_keys k ON k.id = d.api_key_id
                          WHERE d.created_at >= ? AND d.created_at < ? AND k.account_id IS NOT NULL
                          GROUP BY k.account_id) dr ON dr.account_id = a.id
            ON CONFLICT (account_id, month) DO UPDATE SET
                subjects = EXCLUDED.subjects,
                consent_events = EXCLUDED.consent_events,
                audit_events = EXCLUDED.audit_events,
                dsr_requests = EXCLUDED.dsr_requests,
                computed_at = EXCLUDED.computed_at
            """;

    private final JdbcTemplate jdbc;

    public JdbcUsageStats(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public MonthCounts countWindow(UUID accountId, Instant start, Instant end) {
        OffsetDateTime s = start.atOffset(ZoneOffset.UTC);
        OffsetDateTime e = end.atOffset(ZoneOffset.UTC);
        return jdbc.queryForObject(COUNT_SQL, (rs, i) -> new MonthCounts(
                        rs.getLong("subjects"), rs.getLong("consent_events"),
                        rs.getLong("audit_events"), rs.getLong("dsr_requests")),
                accountId, s, e, accountId, s, e, accountId, s, e, accountId, s, e);
    }

    /** Upserts the rollup row of every account for the given month window. */
    public void rollUpAllAccounts(LocalDate month, Instant start, Instant end) {
        OffsetDateTime s = start.atOffset(ZoneOffset.UTC);
        OffsetDateTime e = end.atOffset(ZoneOffset.UTC);
        jdbc.update(ROLLUP_SQL, month, s, e, s, e, s, e, s, e);
    }

    public record KeyPlan(UUID accountId, String plan) {
    }

    public record MeteredAccount(UUID id, String email) {
    }

    /**
     * The plan context of an API key: its account id and that account's plan.
     * {@code accountId} is null for operator/bootstrap keys — never metered.
     * Returns null when the key does not exist.
     */
    public KeyPlan planForKey(UUID apiKeyId) {
        List<KeyPlan> rows = jdbc.query("""
                        SELECT k.account_id, a.plan FROM api_keys k
                          LEFT JOIN accounts a ON a.id = k.account_id
                         WHERE k.id = ?
                        """,
                (rs, i) -> new KeyPlan(rs.getObject("account_id", UUID.class), rs.getString("plan")),
                apiKeyId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public boolean subjectExists(UUID apiKeyId, String externalId) {
        Boolean exists = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM subjects WHERE api_key_id = ? AND external_id = ?)",
                Boolean.class, apiKeyId, externalId);
        return Boolean.TRUE.equals(exists);
    }

    public long activeKeyCount(UUID accountId) {
        Long n = jdbc.queryForObject(
                "SELECT count(*) FROM api_keys WHERE account_id = ? AND revoked_at IS NULL",
                Long.class, accountId);
        return n == null ? 0 : n;
    }

    /** ACTIVE accounts on the FREE plan — the population the usage-alert job meters. */
    public List<MeteredAccount> activeFreeAccounts() {
        return jdbc.query("SELECT id, email FROM accounts WHERE status = 'ACTIVE' AND plan = 'FREE'",
                (rs, i) -> new MeteredAccount(rs.getObject("id", UUID.class), rs.getString("email")));
    }

    public boolean alertAlreadySent(UUID accountId, LocalDate month, String metric, int thresholdPct) {
        Boolean sent = jdbc.queryForObject("""
                        SELECT EXISTS (SELECT 1 FROM usage_alerts
                          WHERE account_id = ? AND month = ? AND metric = ? AND threshold_pct = ?)
                        """,
                Boolean.class, accountId, month, metric, thresholdPct);
        return Boolean.TRUE.equals(sent);
    }

    public void recordAlert(UUID accountId, LocalDate month, String metric, int thresholdPct) {
        jdbc.update("""
                        INSERT INTO usage_alerts (account_id, month, metric, threshold_pct)
                        VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING
                        """,
                accountId, month, metric, thresholdPct);
    }

    /** Stored months for an account, newest first. */
    public List<RollupRow> history(UUID accountId, int limit) {
        return jdbc.query("""
                        SELECT month, subjects, consent_events, audit_events, dsr_requests
                          FROM usage_rollups WHERE account_id = ?
                         ORDER BY month DESC LIMIT ?
                        """,
                (rs, i) -> new RollupRow(rs.getObject("month", LocalDate.class),
                        rs.getLong("subjects"), rs.getLong("consent_events"),
                        rs.getLong("audit_events"), rs.getLong("dsr_requests")),
                accountId, limit);
    }
}
