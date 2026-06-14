package io.github.thgrcarvalho.zelo.starter;

import java.time.Instant;
import java.util.Map;

/**
 * One entry in the hash-chained audit trail ({@code GET /v1/audit}). Each entry's
 * {@code entryHash} is computed over the previous entry's hash, so the chain is
 * tamper-evident — see {@code GET /v1/audit/verify}.
 *
 * <p>{@code payload} is the (PII-free) event detail as a plain map, so it
 * re-serialises under the consumer's own JSON settings and the public API carries
 * no JSON-library types.</p>
 *
 * @param prevHash   the prior entry's hash (genesis is 64 zeros)
 * @param entryHash  this entry's hash
 */
public record ZeloAuditEntry(long id, String eventType, Map<String, Object> payload,
                             Instant occurredAt, String prevHash, String entryHash) {
}
