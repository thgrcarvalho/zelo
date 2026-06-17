package io.github.thgrcarvalho.zelo.domain.crypto;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;

/**
 * Password hashing for integrator accounts — pure JDK, no new dependency.
 *
 * <p>Uses PBKDF2WithHmacSHA256 with a per-hash random salt and a high iteration
 * count. {@link #hash} returns a self-describing string
 * {@code pbkdf2-sha256$<iters>$<b64 salt>$<b64 hash>} so the parameters travel
 * with the stored value and can be raised later without a migration (old hashes
 * still verify against their own embedded iteration count). {@link #verify} is
 * constant-time ({@link MessageDigest#isEqual}).</p>
 */
public final class Passwords {

    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final String ID = "pbkdf2-sha256";
    private static final int ITERATIONS = 210_000;
    private static final int SALT_BYTES = 16;
    private static final int HASH_BITS = 256;

    private static final SecureRandom RANDOM = new SecureRandom();
    // Standard Base64 (no '$' in its alphabet) so '$' stays a safe field separator.
    private static final Base64.Encoder ENCODER = Base64.getEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getDecoder();

    private Passwords() {
    }

    /** Hash a raw password into the self-describing PBKDF2 format. */
    public static String hash(String raw) {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] hash = pbkdf2(raw.toCharArray(), salt, ITERATIONS, HASH_BITS);
        return ID + "$" + ITERATIONS + "$" + ENCODER.encodeToString(salt) + "$" + ENCODER.encodeToString(hash);
    }

    /**
     * Verify {@code raw} against a value produced by {@link #hash}. Returns
     * {@code false} (never throws) on any malformed or mismatching input, so it is
     * safe to call with attacker-controlled stored values.
     */
    public static boolean verify(String raw, String stored) {
        if (raw == null || stored == null) {
            return false;
        }
        String[] parts = stored.split("\\$");
        if (parts.length != 4 || !ID.equals(parts[0])) {
            return false;
        }
        int iterations;
        byte[] salt;
        byte[] expected;
        try {
            iterations = Integer.parseInt(parts[1]);
            salt = DECODER.decode(parts[2]);
            expected = DECODER.decode(parts[3]);
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (iterations <= 0 || salt.length == 0 || expected.length == 0) {
            return false;
        }
        byte[] actual = pbkdf2(raw.toCharArray(), salt, iterations, expected.length * 8);
        return MessageDigest.isEqual(actual, expected);
    }

    private static byte[] pbkdf2(char[] password, byte[] salt, int iterations, int bits) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, bits);
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalStateException("PBKDF2 is required but unavailable", e);
        }
    }
}
