package io.github.thgrcarvalho.zelo.domain.audit;

import io.github.thgrcarvalho.zelo.domain.crypto.Hashes;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/**
 * The audit hash-chain algorithm. Pure and self-contained so the proof trail can
 * be reproduced — and independently re-implemented — from the published rule.
 *
 * <pre>
 *   entry_hash = SHA-256( prev_hash \n event_type \n canonical_json(payload) \n occurred_at )
 * </pre>
 *
 * <p>The four components are joined with a {@code '\n'} separator (so field
 * boundaries are unambiguous), where:</p>
 * <ul>
 *   <li>{@code prev_hash} is the previous entry's {@code entry_hash}, or
 *       {@link #GENESIS_PREV_HASH} for the first entry in a chain;</li>
 *   <li>{@code canonical_json(payload)} is {@link CanonicalJson};</li>
 *   <li>{@code occurred_at} is the instant rendered by {@link #formatOccurredAt}
 *       (ISO-8601, truncated to microseconds to survive the Postgres round trip).</li>
 * </ul>
 */
public final class HashChain {

    /** The {@code prev_hash} of the first entry in any chain: 64 zero characters. */
    public static final String GENESIS_PREV_HASH = "0".repeat(64);

    private HashChain() {
    }

    /** Compute the {@code entry_hash} for an entry given its preceding hash. */
    public static String entryHash(String prevHash, String eventType,
                                   String canonicalPayload, String occurredAtIso) {
        String material = prevHash + '\n' + eventType + '\n' + canonicalPayload + '\n' + occurredAtIso;
        return Hashes.sha256Hex(material);
    }

    /**
     * Render an instant for hashing. Truncated to microseconds because Postgres
     * {@code timestamptz} has microsecond resolution; truncating here means the
     * value hashed at write time and the value read back at verify time render
     * to the exact same string.
     */
    public static String formatOccurredAt(Instant occurredAt) {
        return DateTimeFormatter.ISO_INSTANT.format(occurredAt.truncatedTo(ChronoUnit.MICROS));
    }
}
