package io.github.thgrcarvalho.zelo.infrastructure.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.thgrcarvalho.zelo.domain.audit.AuditEntry;
import io.github.thgrcarvalho.zelo.domain.audit.AuditTrail;
import io.github.thgrcarvalho.zelo.domain.audit.CanonicalJson;
import io.github.thgrcarvalho.zelo.domain.audit.ChainVerification;
import io.github.thgrcarvalho.zelo.domain.audit.HashChain;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * JDBC adapter for the append-only, hash-chained audit log.
 *
 * <p>Appends to a given integrator's chain are serialized by a transaction-scoped
 * Postgres advisory lock keyed on the api_key, so the read-tip-then-append is
 * race-free; the {@code uq_audit_chain_link} unique constraint is the structural
 * backstop. Verification recomputes the whole chain from the stored rows using the
 * same {@link HashChain} + {@link CanonicalJson} rule used at write time.</p>
 */
@Repository
public class JdbcAuditTrail implements AuditTrail {

    // Namespace for pg_advisory_xact_lock(int4, int4) so audit locks never
    // collide with any other advisory lock in the system.
    private static final int ADVISORY_LOCK_NAMESPACE = 0x5A41; // 'ZA' — Zelo Audit

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcAuditTrail(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public AuditEntry append(UUID apiKeyId, String eventType, JsonNode payload, Instant occurredAt) {
        // Serialize appends to this chain for the duration of the surrounding
        // transaction. Released automatically on commit/rollback.
        jdbc.query("SELECT pg_advisory_xact_lock(?, ?)",
                (ResultSetExtractor<Void>) rs -> null,
                ADVISORY_LOCK_NAMESPACE, apiKeyId.hashCode());

        String prevHash = jdbc.query(
                "SELECT entry_hash FROM audit_log WHERE api_key_id = ? ORDER BY id DESC LIMIT 1",
                (ResultSetExtractor<String>) rs -> rs.next() ? rs.getString(1) : HashChain.GENESIS_PREV_HASH,
                apiKeyId);

        Instant occurred = occurredAt.truncatedTo(ChronoUnit.MICROS);
        String canonical = CanonicalJson.canonicalize(payload);
        String occurredIso = HashChain.formatOccurredAt(occurred);
        String entryHash = HashChain.entryHash(prevHash, eventType, canonical, occurredIso);

        Long id = jdbc.queryForObject(
                "INSERT INTO audit_log (api_key_id, event_type, payload, occurred_at, prev_hash, entry_hash) "
                        + "VALUES (?, ?, ?::jsonb, ?, ?, ?) RETURNING id",
                Long.class,
                apiKeyId, eventType, canonical,
                OffsetDateTime.ofInstant(occurred, ZoneOffset.UTC), prevHash, entryHash);

        return new AuditEntry(id, apiKeyId, eventType, payload, occurred, prevHash, entryHash);
    }

    @Override
    public List<AuditEntry> list(UUID apiKeyId, Instant from, Instant to) {
        StringBuilder sql = new StringBuilder(
                "SELECT id, api_key_id, event_type, payload, occurred_at, prev_hash, entry_hash "
                        + "FROM audit_log WHERE api_key_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(apiKeyId);
        if (from != null) {
            sql.append(" AND occurred_at >= ?");
            args.add(OffsetDateTime.ofInstant(from, ZoneOffset.UTC));
        }
        if (to != null) {
            sql.append(" AND occurred_at < ?");
            args.add(OffsetDateTime.ofInstant(to, ZoneOffset.UTC));
        }
        sql.append(" ORDER BY id ASC");
        return jdbc.query(sql.toString(), entryRowMapper(), args.toArray());
    }

    @Override
    public ChainVerification verify(UUID apiKeyId) {
        // Read raw rows in chain order and recompute. We read the payload as the
        // stored jsonb text (not a mapped object) so verification depends on
        // nothing but the bytes on disk.
        List<RawRow> rows = jdbc.query(
                "SELECT id, event_type, payload::text AS payload, occurred_at, prev_hash, entry_hash "
                        + "FROM audit_log WHERE api_key_id = ? ORDER BY id ASC",
                (rs, n) -> new RawRow(
                        rs.getLong("id"),
                        rs.getString("event_type"),
                        rs.getString("payload"),
                        rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                        rs.getString("prev_hash"),
                        rs.getString("entry_hash")),
                apiKeyId);

        String expectedPrev = HashChain.GENESIS_PREV_HASH;
        long checked = 0;
        for (RawRow row : rows) {
            checked++;
            if (!expectedPrev.equals(row.prevHash())) {
                return ChainVerification.broken(checked, row.id(),
                        "prev_hash does not match the previous entry's entry_hash — the chain link is broken "
                                + "(an entry was inserted, deleted or reordered).");
            }
            String recomputed = HashChain.entryHash(
                    row.prevHash(), row.eventType(),
                    CanonicalJson.canonicalize(row.payload()),
                    HashChain.formatOccurredAt(row.occurredAt()));
            if (!recomputed.equals(row.entryHash())) {
                return ChainVerification.broken(checked, row.id(),
                        "entry_hash does not match the recomputed hash — this entry was tampered with.");
            }
            expectedPrev = row.entryHash();
        }
        return ChainVerification.valid(checked);
    }

    private RowMapper<AuditEntry> entryRowMapper() {
        return (rs, rowNum) -> new AuditEntry(
                rs.getLong("id"),
                rs.getObject("api_key_id", UUID.class),
                rs.getString("event_type"),
                parse(rs.getString("payload")),
                rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                rs.getString("prev_hash"),
                rs.getString("entry_hash"));
    }

    private JsonNode parse(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("Stored audit payload is not valid JSON", e);
        }
    }

    private record RawRow(long id, String eventType, String payload, Instant occurredAt,
                          String prevHash, String entryHash) {
    }
}
