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
 * payload, secret))}, where the payload is {@code <accountId>:<expiryEpochSeconds>}.
 *
 * <p>The server keeps no session table: {@link #verify} recomputes the HMAC and
 * checks expiry, so a forged or expired token is rejected without a lookup. The
 * account's current role/status are loaded fresh from the DB on each request (by
 * the auth filter), so an approval or revocation takes effect immediately, without
 * re-issuing the token.</p>
 *
 * <p><b>Fail-closed:</b> constructed with a blank secret (unconfigured deploy),
 * {@link #verify} always returns empty and {@link #mint} throws — no one can
 * authenticate to {@code /account} until {@code zelo.auth.session-secret} is set.</p>
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

    /** Mint a token for {@code accountId} that expires after {@code ttl}. */
    public String mint(UUID accountId, Duration ttl) {
        if (secret == null) {
            throw new IllegalStateException("Session secret is not configured (zelo.auth.session-secret)");
        }
        long exp = Instant.now().plus(ttl).getEpochSecond();
        String payload = ENCODER.encodeToString((accountId + ":" + exp).getBytes(StandardCharsets.UTF_8));
        String signature = ENCODER.encodeToString(hmac(payload));
        return payload + "." + signature;
    }

    /**
     * Verify a token and return its account id, or empty if the secret is
     * unconfigured, the token is malformed, the signature is wrong, or it has
     * expired. Never throws.
     */
    public Optional<UUID> verify(String token) {
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

        String decoded = new String(payloadBytes, StandardCharsets.UTF_8);
        int colon = decoded.lastIndexOf(':');
        if (colon <= 0) {
            return Optional.empty();
        }
        long exp;
        try {
            exp = Long.parseLong(decoded.substring(colon + 1));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        if (Instant.now().getEpochSecond() >= exp) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(decoded.substring(0, colon)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
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
}
