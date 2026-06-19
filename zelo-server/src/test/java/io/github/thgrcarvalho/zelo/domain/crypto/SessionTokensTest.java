package io.github.thgrcarvalho.zelo.domain.crypto;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SessionTokensTest {

    private static final String SECRET = "a-strong-enough-session-secret-for-tests";
    private final SessionTokens tokens = new SessionTokens(SECRET);

    @Test
    void roundTripsTheAccountIdAndWatermark() {
        UUID accountId = UUID.randomUUID();
        String token = tokens.mint(accountId, 1_700_000_000_123L, Duration.ofHours(1));
        var claims = tokens.verify(token);
        assertThat(claims).isPresent();
        assertThat(claims.get().accountId()).isEqualTo(accountId);
        assertThat(claims.get().passwordWatermarkMillis()).isEqualTo(1_700_000_000_123L);
    }

    @Test
    void rejectsAnExpiredToken() {
        String token = tokens.mint(UUID.randomUUID(), 0L, Duration.ofSeconds(-1));
        assertThat(tokens.verify(token)).isEmpty();
    }

    @Test
    void rejectsATamperedToken() {
        String token = tokens.mint(UUID.randomUUID(), 0L, Duration.ofHours(1));
        // Flip a char in the PAYLOAD (signed content) — the HMAC is computed over the
        // payload string, so any change there deterministically fails verification.
        // (Flipping the last signature char only toggles base64 padding bits ~1/16 of
        // the time and can decode to the same bytes — a flaky tamper.)
        String tampered = (token.charAt(0) == 'A' ? "B" : "A") + token.substring(1);
        assertThat(tokens.verify(tampered)).isEmpty();
    }

    @Test
    void rejectsATokenSignedWithADifferentSecret() {
        String token = new SessionTokens("a-different-secret").mint(UUID.randomUUID(), 0L, Duration.ofHours(1));
        assertThat(tokens.verify(token)).isEmpty();
    }

    @Test
    void rejectsMalformedTokensWithoutThrowing() {
        assertThat(tokens.verify(null)).isEmpty();
        assertThat(tokens.verify("")).isEmpty();
        assertThat(tokens.verify("no-dot")).isEmpty();
        assertThat(tokens.verify(".onlysig")).isEmpty();
        assertThat(tokens.verify("onlypayload.")).isEmpty();
        assertThat(tokens.verify("!!!.@@@")).isEmpty();
    }

    @Test
    void rejectsLegacyTwoFieldAndOverlongPayloads() {
        long futureExp = System.currentTimeMillis() / 1000 + 3600;
        // Correctly signed, but only 2 fields (the pre-watermark format) — must fail closed.
        assertThat(tokens.verify(signed(UUID.randomUUID() + ":" + futureExp))).isEmpty();
        // Four fields is also malformed.
        assertThat(tokens.verify(signed(UUID.randomUUID() + ":" + futureExp + ":0:extra"))).isEmpty();
    }

    @Test
    void failsClosedWhenUnconfigured() {
        SessionTokens unconfigured = new SessionTokens("  ");
        assertThat(unconfigured.isConfigured()).isFalse();
        assertThat(unconfigured.verify(tokens.mint(UUID.randomUUID(), 0L, Duration.ofHours(1)))).isEmpty();
        assertThatThrownBy(() -> unconfigured.mint(UUID.randomUUID(), 0L, Duration.ofHours(1)))
                .isInstanceOf(IllegalStateException.class);
    }

    /** Build a token with an arbitrary (correctly-signed) payload, to exercise field-count rejection. */
    private static String signed(String rawPayload) {
        try {
            Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
            String payload = enc.encodeToString(rawPayload.getBytes(StandardCharsets.UTF_8));
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String sig = enc.encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
            return payload + "." + sig;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
