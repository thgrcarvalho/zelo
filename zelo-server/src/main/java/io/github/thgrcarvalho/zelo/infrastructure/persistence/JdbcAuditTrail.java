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

import java.sql.PreparedStatement;
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

    // Cursor fetch size for streaming chain verification, so heap stays bounded on
    // long chains. Honoured only inside the (read-only) transaction verify runs in.
    private static final int VERIFY_FETCH_SIZE = 1_000;

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
        // transaction (released on commit/rollback). The 64-bit lock key folds the
        // whole UUID — far lower collision odds than the 32-bit UUID.hashCode().
        long lockKey = apiKeyId.getMostSignificantBits() ^ apiKeyId.getLeastSignificantBits();
        jdbc.query("SELECT pg_advisory_xact_lock(?)", (ResultSetExtractor<Void>) rs -> null, lockKey);

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
    public List<AuditEntry> list(UUID apiKeyId, Instant from, Instant to, Long afterId, int limit) {
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
        if (afterId != null) {
            // Keyset pagination: callers page by passing the last id they saw.
            sql.append(" AND id > ?");
            args.add(afterId);
        }
        sql.append(" ORDER BY id ASC LIMIT ?");
        args.add(limit);
        return jdbc.query(sql.toString(), entryRowMapper(), args.toArray());
    }

    @Override
    public ChainVerification verify(UUID apiKeyId) {
        // Stream rows in chain order and recompute, holding only the running link in
        // memory (never the whole chain) so verification stays O(1) heap regardless of
        // chain length. The payload is read as stored jsonb text so the result depends
        // on nothing but the bytes on disk. Cursor streaming needs both the fetch size
        // and the read-only transaction this runs in; it stops at the first broken link.
        return jdbc.query(
                con -> {
                    PreparedStatement ps = con.prepareStatement(
                            "SELECT id, event_type, payload::text AS payload, occurred_at, prev_hash, entry_hash "
                                    + "FROM audit_log WHERE api_key_id = ? ORDER BY id ASC");
                    ps.setFetchSize(VERIFY_FETCH_SIZE);
                    ps.setObject(1, apiKeyId);
                    return ps;
                },
                (ResultSetExtractor<ChainVerification>) rs -> {
                    String expectedPrev = HashChain.GENESIS_PREV_HASH;
                    long checked = 0;
                    while (rs.next()) {
                        checked++;
                        long id = rs.getLong("id");
                        String prevHash = rs.getString("prev_hash");
                        String entryHash = rs.getString("entry_hash");
                        if (!expectedPrev.equals(prevHash)) {
                            return ChainVerification.broken(checked, id,
                                    "prev_hash does not match the previous entry's entry_hash — the chain link "
                                            + "is broken (an entry was inserted, deleted or reordered).");
                        }
                        String recomputed = HashChain.entryHash(
                                prevHash, rs.getString("event_type"),
                                CanonicalJson.canonicalize(rs.getString("payload")),
                                HashChain.formatOccurredAt(
                                        rs.getObject("occurred_at", OffsetDateTime.class).toInstant()));
                        if (!recomputed.equals(entryHash)) {
                            return ChainVerification.broken(checked, id,
                                    "entry_hash does not match the recomputed hash — this entry was tampered with.");
                        }
                        expectedPrev = entryHash;
                    }
                    return ChainVerification.valid(checked);
                });
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
}
