package io.github.thgrcarvalho.zelo.domain.crypto;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Generates fresh, opaque API-key strings for runtime provisioning. The raw
 * value is shown to the caller exactly once; only its SHA-256 hash
 * ({@link Hashes}) is ever stored. 256 bits of entropy, URL-safe Base64, with a
 * {@code zk_} prefix so a leaked key is recognizable to humans and secret
 * scanners.
 */
public final class RawKeys {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final int ENTROPY_BYTES = 32;
    private static final String PREFIX = "zk_";

    private RawKeys() {
    }

    /** A new opaque key: {@code zk_} followed by URL-safe Base64 of 32 random bytes. */
    public static String generate() {
        byte[] bytes = new byte[ENTROPY_BYTES];
        RANDOM.nextBytes(bytes);
        return PREFIX + ENCODER.encodeToString(bytes);
    }
}
