package io.github.thgrcarvalho.zelo.domain.audit;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/**
 * One entry in an integrator's audit chain.
 *
 * @param id        chain position (ascending within an api_key)
 * @param apiKeyId  the integrator this chain belongs to
 * @param eventType e.g. {@code "consent.granted"}
 * @param payload   the (PII-free) event body
 * @param occurredAt when the audited event happened
 * @param prevHash  the preceding entry's {@code entryHash} (or genesis)
 * @param entryHash this entry's hash
 */
public record AuditEntry(
        long id,
        UUID apiKeyId,
        String eventType,
        JsonNode payload,
        Instant occurredAt,
        String prevHash,
        String entryHash
) {
}
