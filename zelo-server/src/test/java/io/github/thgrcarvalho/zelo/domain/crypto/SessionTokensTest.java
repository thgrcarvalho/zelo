package io.github.thgrcarvalho.zelo.domain.crypto;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SessionTokensTest {

    private final SessionTokens tokens = new SessionTokens("a-strong-enough-session-secret-for-tests");

    @Test
    void roundTripsTheAccountId() {
        UUID accountId = UUID.randomUUID();
        String token = tokens.mint(accountId, Duration.ofHours(1));
        assertThat(tokens.verify(token)).contains(accountId);
    }

    @Test
    void rejectsAnExpiredToken() {
        String token = tokens.mint(UUID.randomUUID(), Duration.ofSeconds(-1));
        assertThat(tokens.verify(token)).isEmpty();
    }

    @Test
    void rejectsATamperedSignature() {
        String token = tokens.mint(UUID.randomUUID(), Duration.ofHours(1));
        String tampered = token.substring(0, token.length() - 1)
                + (token.endsWith("A") ? "B" : "A");
        assertThat(tokens.verify(tampered)).isEmpty();
    }

    @Test
    void rejectsATokenSignedWithADifferentSecret() {
        String token = new SessionTokens("a-different-secret").mint(UUID.randomUUID(), Duration.ofHours(1));
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
    void failsClosedWhenUnconfigured() {
        SessionTokens unconfigured = new SessionTokens("  ");
        assertThat(unconfigured.isConfigured()).isFalse();
        Optional<UUID> result = unconfigured.verify(tokens.mint(UUID.randomUUID(), Duration.ofHours(1)));
        assertThat(result).isEmpty();
        assertThatThrownBy(() -> unconfigured.mint(UUID.randomUUID(), Duration.ofHours(1)))
                .isInstanceOf(IllegalStateException.class);
    }
}
