package io.github.thgrcarvalho.zelo.domain.audit;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class HashChainTest {

    private static final String GENESIS = HashChain.GENESIS_PREV_HASH;

    @Test
    void genesisIs64ZeroCharacters() {
        assertThat(GENESIS).isEqualTo("0".repeat(64)).hasSize(64);
    }

    @Test
    void isDeterministic() {
        String a = HashChain.entryHash(GENESIS, "consent.granted", "{\"a\":1}", "2026-01-01T00:00:00Z");
        String b = HashChain.entryHash(GENESIS, "consent.granted", "{\"a\":1}", "2026-01-01T00:00:00Z");
        assertThat(a).isEqualTo(b).hasSize(64);
    }

    @Test
    void changesWhenAnyComponentChanges() {
        String base = HashChain.entryHash(GENESIS, "t", "{}", "2026-01-01T00:00:00Z");
        assertThat(HashChain.entryHash(GENESIS, "t2", "{}", "2026-01-01T00:00:00Z")).isNotEqualTo(base);
        assertThat(HashChain.entryHash(GENESIS, "t", "{\"a\":1}", "2026-01-01T00:00:00Z")).isNotEqualTo(base);
        assertThat(HashChain.entryHash("f".repeat(64), "t", "{}", "2026-01-01T00:00:00Z")).isNotEqualTo(base);
        assertThat(HashChain.entryHash(GENESIS, "t", "{}", "2026-01-02T00:00:00Z")).isNotEqualTo(base);
    }

    @Test
    void matchesThePublishedFormula() throws Exception {
        String prev = GENESIS;
        String type = "consent.granted";
        String payload = "{\"purposeKey\":\"billing\"}";
        String occurred = "2026-01-01T00:00:00Z";

        String material = prev + '\n' + type + '\n' + payload + '\n' + occurred;
        String expected = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8)));

        assertThat(HashChain.entryHash(prev, type, payload, occurred)).isEqualTo(expected);
    }

    @Test
    void formatsOccurredAtTruncatedToMicroseconds() {
        Instant nanos = Instant.parse("2026-01-01T00:00:00.123456789Z");
        assertThat(HashChain.formatOccurredAt(nanos)).isEqualTo("2026-01-01T00:00:00.123456Z");
    }
}
