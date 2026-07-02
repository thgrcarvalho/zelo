package io.github.thgrcarvalho.zelo.starter;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Validates a Zelo webhook signature: an HMAC-SHA256 digest of the raw request
 * body, hex-encoded, carried in the {@code X-Zelo-Signature} header. A
 * {@code sha256=}-style prefix on the header value is accepted and hex casing
 * is ignored; the comparison itself is constant-time.
 */
public final class ZeloWebhookValidator {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final byte[] secret;

    public ZeloWebhookValidator(String hmacSecret) {
        Objects.requireNonNull(hmacSecret, "hmacSecret must not be null");
        if (hmacSecret.isBlank()) {
            throw new IllegalArgumentException("hmacSecret must not be blank");
        }
        this.secret = hmacSecret.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * @param body              the raw request body the signature was computed over
     * @param providedSignature the {@code X-Zelo-Signature} header value (may be null)
     * @return true when {@code providedSignature} matches the HMAC-SHA256 of {@code body}
     */
    public boolean isValid(byte[] body, String providedSignature) {
        if (body == null || providedSignature == null || providedSignature.isBlank()) {
            return false;
        }
        byte[] expected = computeHex(body).getBytes(StandardCharsets.UTF_8);
        byte[] provided = normalise(providedSignature).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, provided);
    }

    private String computeHex(byte[] body) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("Failed to compute HMAC-SHA256", e);
        }
    }

    /** Accepts either a bare hex digest or a {@code sha256=<hex>} prefixed value. */
    private static String normalise(String signature) {
        int eq = signature.indexOf('=');
        return (eq >= 0 ? signature.substring(eq + 1) : signature).toLowerCase();
    }
}
