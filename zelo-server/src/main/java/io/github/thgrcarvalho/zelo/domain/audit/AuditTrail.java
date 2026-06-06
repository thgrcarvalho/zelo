package io.github.thgrcarvalho.zelo.domain.audit;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Port for the append-only, hash-chained audit log. Implemented with explicit
 * SQL (no ORM) so the append-only + chaining discipline is enforced directly.
 */
public interface AuditTrail {

    /**
     * Append an entry to {@code apiKeyId}'s chain, linking it to the current tip.
     * Must run inside the same transaction as the business write it records, so
     * the two commit (or roll back) atomically.
     */
    AuditEntry append(UUID apiKeyId, String eventType, JsonNode payload, Instant occurredAt);

    /**
     * Export a chain in order. {@code from}/{@code to} (inclusive/exclusive) bound
     * {@code occurred_at}; either may be {@code null} for an open end.
     */
    List<AuditEntry> list(UUID apiKeyId, Instant from, Instant to);

    /** Recompute the entire chain for {@code apiKeyId} and report its integrity. */
    ChainVerification verify(UUID apiKeyId);
}
