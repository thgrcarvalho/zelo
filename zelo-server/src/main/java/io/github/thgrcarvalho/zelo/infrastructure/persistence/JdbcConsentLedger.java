package io.github.thgrcarvalho.zelo.infrastructure.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.thgrcarvalho.zelo.domain.consent.ConsentAction;
import io.github.thgrcarvalho.zelo.domain.consent.ConsentEvent;
import io.github.thgrcarvalho.zelo.domain.consent.ConsentLedger;
import io.github.thgrcarvalho.zelo.domain.consent.ConsentStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * JDBC adapter for the append-only consent ledger. Current state is derived, not
 * stored: it is the latest event per purpose (DISTINCT ON).
 */
@Repository
public class JdbcConsentLedger implements ConsentLedger {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcConsentLedger(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public ConsentEvent append(ConsentEvent event) {
        Instant occurred = event.occurredAt().truncatedTo(ChronoUnit.MICROS);
        String metadata = event.metadata() == null ? "{}" : event.metadata().toString();

        Long id = jdbc.queryForObject(
                "INSERT INTO consent_events (subject_id, purpose_id, action, source, occurred_at, metadata) "
                        + "VALUES (?, ?, ?::consent_action, ?, ?, ?::jsonb) RETURNING id",
                Long.class,
                event.subjectId(), event.purposeId(), event.action().name(), event.source(),
                OffsetDateTime.ofInstant(occurred, ZoneOffset.UTC), metadata);

        return new ConsentEvent(id, event.subjectId(), event.purposeId(), event.action(),
                event.source(), occurred, event.metadata());
    }

    @Override
    public List<ConsentEvent> history(UUID subjectId, UUID purposeId) {
        StringBuilder sql = new StringBuilder(
                "SELECT id, subject_id, purpose_id, action, source, occurred_at, metadata "
                        + "FROM consent_events WHERE subject_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(subjectId);
        if (purposeId != null) {
            sql.append(" AND purpose_id = ?");
            args.add(purposeId);
        }
        sql.append(" ORDER BY occurred_at ASC, id ASC");
        return jdbc.query(sql.toString(), eventRowMapper(), args.toArray());
    }

    @Override
    public List<ConsentStatus> currentState(UUID subjectId) {
        return jdbc.query(
                "SELECT p.key AS purpose_key, latest.action, latest.source, latest.occurred_at "
                        + "FROM ( "
                        + "  SELECT DISTINCT ON (purpose_id) purpose_id, action, source, occurred_at "
                        + "  FROM consent_events WHERE subject_id = ? "
                        + "  ORDER BY purpose_id, occurred_at DESC, id DESC "
                        + ") latest JOIN purposes p ON p.id = latest.purpose_id "
                        + "ORDER BY p.key ASC",
                (rs, n) -> {
                    ConsentAction action = ConsentAction.valueOf(rs.getString("action"));
                    return new ConsentStatus(
                            rs.getString("purpose_key"),
                            action == ConsentAction.GRANT,
                            action,
                            rs.getString("source"),
                            rs.getObject("occurred_at", OffsetDateTime.class).toInstant());
                },
                subjectId);
    }

    private RowMapper<ConsentEvent> eventRowMapper() {
        return (rs, rowNum) -> new ConsentEvent(
                rs.getLong("id"),
                rs.getObject("subject_id", UUID.class),
                rs.getObject("purpose_id", UUID.class),
                ConsentAction.valueOf(rs.getString("action")),
                rs.getString("source"),
                rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                parse(rs.getString("metadata")));
    }

    private JsonNode parse(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("Stored consent metadata is not valid JSON", e);
        }
    }
}
