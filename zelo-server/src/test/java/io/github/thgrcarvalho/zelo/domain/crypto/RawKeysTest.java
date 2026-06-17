package io.github.thgrcarvalho.zelo.domain.crypto;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RawKeysTest {

    @Test
    void generatesUniquePrefixedUrlSafeKeys() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            String key = RawKeys.generate();
            // zk_ + URL-safe Base64 of 32 bytes (no padding) = 43 chars of payload.
            assertThat(key).matches("zk_[A-Za-z0-9_-]{43}");
            assertThat(seen.add(key)).as("keys must be unique").isTrue();
        }
    }

    @Test
    void roundTripsThroughTheSameHashUsedByAuth() {
        String key = RawKeys.generate();
        assertThat(Hashes.sha256Hex(key)).isEqualTo(Hashes.sha256Hex(key)).hasSize(64);
    }
}
