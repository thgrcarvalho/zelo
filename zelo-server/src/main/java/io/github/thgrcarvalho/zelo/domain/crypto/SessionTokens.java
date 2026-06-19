package io.github.thgrcarvalho.zelo.domain.crypto;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/**
 * Stateless, signed session tokens for the {@code /account} dashboard — pure JDK,
 * no JWT library. A token is {@code b64url(payload) + "." + b64url(HMAC-SHA256(
 * payload, secret))}, where the payload is
 * {@code <accountId>:<expiryEpochSeconds>:<passwordWatermarkEpochMillis>}.
 *
 * <p>The server keeps no session table: {@link #verify} recomputes the HMAC and
 * checks expiry, so a forged or expired token is rejected without a lookup. The
 * account's current status is loaded fresh from the DB on each request (by the auth
 * filter), so a status change takes effect immediately without re-issuing.</p>
 *
 * <p>The <b>password watermark</b> is the account's {@code passwordChangedAt} at
 * mint time. The filter compares it for exact equality against the account's
 * current value; a password reset advances that value, so every previously-issued
 * token stops matching and is rejected — a stateless "log out all sessions".</p>
 *
 * <p><b>Fail-closed:</b> constructed with a blank secret (unconfigured deploy),
 * {@link #verify} always returns empty and {@link #mint} throws — no one can
 * authenticate to {@code /account} until {@code zelo.auth.session-secret} is set.
 * Legacy 2-field tokens (pre-watermark) are rejected.</p>
 */
public final class SessionTokens {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    /** HMAC key bytes, or {@code null} when unconfigured (blank secret). */
    private final byte[] secret;

    public SessionTokens(String secret) {
        this.secret = (secret == null || secret.isBlank())
                ? null
                : secret.getBytes(StandardCharsets.UTF_8);
    }

    public boolean isConfigured() {
        return secret != null;
    }

    /**
     * Mint a token for {@code accountId} that expires after {@code ttl}, carrying
     * the account's password watermark (its {@code passwordChangedAt} as epoch
     * millis) so a later password change invalidates it.
     */
    public String mint(UUID accountId, long passwordWatermarkMillis, Duration ttl) {
        if (secret == null) {
            throw new IllegalStateException("Session secret is not configured (zelo.auth.session-secret)");
        }
        long exp = Instant.now().plus(ttl).getEpochSecond();
        String raw = accountId + ":" + exp + ":" + passwordWatermarkMillis;
        String payload = ENCODER.encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        String signature = ENCODER.encodeToString(hmac(payload));
        return payload + "." + signature;
    }

    /**
     * Verify a token and return its claims, or empty if the secret is unconfigured,
     * the token is malformed (incl. legacy 2-field tokens), the signature is wrong,
     * or it has expired. Never throws. The caller MUST still check the password
     * watermark against the account's current value.
     */
    public Optional<Claims> verify(String token) {
        if (secret == null || token == null) {
            return Optional.empty();
        }
        int dot = token.indexOf('.');
        if (dot <= 0 || dot >= token.length() - 1) {
            return Optional.empty();
        }
        String payload = token.substring(0, dot);
        String presentedSig = token.substring(dot + 1);

        byte[] expectedSig = hmac(payload);
        byte[] presented;
        byte[] payloadBytes;
        try {
            presented = DECODER.decode(presentedSig);
            payloadBytes = DECODER.decode(payload);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        if (!MessageDigest.isEqual(expectedSig, presented)) {
            return Optional.empty();
        }

        // Exactly three colon-delimited fields; a UUID contains no ':' so the split
        // is unambiguous. A 2-field (legacy, pre-watermark) token is rejected.
        String[] parts = new String(payloadBytes, StandardCharsets.UTF_8).split(":");
        if (parts.length != 3) {
            return Optional.empty();
        }
        long exp;
        long watermark;
        UUID accountId;
        try {
            accountId = UUID.fromString(parts[0]);
            exp = Long.parseLong(parts[1]);
            watermark = Long.parseLong(parts[2]);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        if (Instant.now().getEpochSecond() >= exp) {
            return Optional.empty();
        }
        return Optional.of(new Claims(accountId, watermark));
    }

    private byte[] hmac(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("Failed to compute session HMAC", e);
        }
    }

    /** The verified contents of a session token. */
    public record Claims(UUID accountId, long passwordWatermarkMillis) {
    }
}
