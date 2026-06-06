package io.github.thgrcarvalho.zelo.domain.crypto;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Small SHA-256 helper shared by the audit hash chain and API-key hashing.
 */
public final class Hashes {

    private Hashes() {
    }

    /** Lowercase hex SHA-256 of the UTF-8 bytes of {@code input}. */
    public static String sha256Hex(String input) {
        return sha256Hex(input.getBytes(StandardCharsets.UTF_8));
    }

    /** Lowercase hex SHA-256 of {@code input}. */
    public static String sha256Hex(byte[] input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required but unavailable", e);
        }
    }
}
