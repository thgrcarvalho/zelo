package io.github.thgrcarvalho.zelo;

import io.github.thgrcarvalho.zelo.application.AccountService;
import io.github.thgrcarvalho.zelo.domain.account.Account;
import io.github.thgrcarvalho.zelo.domain.account.AccountRepository;
import io.github.thgrcarvalho.zelo.domain.account.AccountToken;
import io.github.thgrcarvalho.zelo.domain.account.AccountTokenRepository;
import io.github.thgrcarvalho.zelo.domain.account.TokenPurpose;
import io.github.thgrcarvalho.zelo.domain.crypto.Hashes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The stale-token purge: a row is removed only when it is <em>both</em> expired and
 * older than the retention window, so live links and rows still inside the
 * cooldown / daily-cap windows are never dropped. Pins retention to 48h and pushes
 * the scheduled run far out so only the explicit {@code purgeStaleTokens()} fires.
 */
@IntegrationTest
@TestPropertySource(properties = {
        "zelo.mail.token-retention-hours=48",
        "zelo.mail.token-purge-interval-ms=3600000"
})
class AccountTokenPurgeIntegrationTest extends AbstractIntegrationTest {

    @Autowired AccountService accountService;
    @Autowired AccountRepository accounts;
    @Autowired AccountTokenRepository tokens;
    @Autowired JdbcTemplate jdbc;

    private UUID accountId;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE accounts, account_tokens CASCADE");
        Account account = accounts.save(Account.signup(
                UUID.randomUUID(), "purge@example.com", "test-hash", "Purge Org", Instant.now()));
        accountId = account.getId();
    }

    @Test
    void deletesOnlyTokensThatAreBothExpiredAndPastTheRetentionWindow() {
        // Expired (TTL elapsed) AND older than the 48h window → purged.
        UUID staleExpired = seed(TokenPurpose.EMAIL_VERIFICATION, Duration.ofHours(50), Duration.ofHours(24), false);
        // Expired too, but used — used-ness must not exempt an old, spent row.
        UUID usedStale = seed(TokenPurpose.PASSWORD_RESET, Duration.ofHours(60), Duration.ofHours(24), true);
        // Expired, but created only 10h ago → still counted by the rolling 24h daily
        // cap; dropping it would corrupt the rate limit, so it must survive.
        UUID expiredButRecent = seed(TokenPurpose.PASSWORD_RESET, Duration.ofHours(10), Duration.ofMinutes(30), false);
        // Old (54h) but a long TTL means it is still a live link → must survive.
        UUID oldButLive = seed(TokenPurpose.EMAIL_VERIFICATION, Duration.ofHours(54), Duration.ofHours(72), false);
        // Old, expiring right at "now" (49h ago + 49h TTL). The purge's <= on expiry
        // matches AccountToken.isExpired (now >= expiresAt), so a just-expired old row
        // is purged, not left to linger a cycle. → deleted.
        UUID expiredJustNow = seed(TokenPurpose.PASSWORD_RESET, Duration.ofHours(49), Duration.ofHours(49), false);
        // Fresh and live → untouched.
        UUID freshLive = seed(TokenPurpose.EMAIL_VERIFICATION, Duration.ofHours(1), Duration.ofHours(24), false);

        assertThat(accountService.purgeStaleTokens()).isEqualTo(3);

        assertThat(tokens.findById(staleExpired)).isEmpty();
        assertThat(tokens.findById(usedStale)).isEmpty();
        assertThat(tokens.findById(expiredJustNow)).isEmpty();
        assertThat(tokens.findById(expiredButRecent)).isPresent();
        assertThat(tokens.findById(oldButLive)).isPresent();
        assertThat(tokens.findById(freshLive)).isPresent();
        assertThat(tokens.count()).isEqualTo(3);
    }

    @Test
    void purgesNothingWhenEveryTokenIsStillLiveOrRecent() {
        seed(TokenPurpose.EMAIL_VERIFICATION, Duration.ofHours(54), Duration.ofHours(72), false); // old but live
        seed(TokenPurpose.PASSWORD_RESET, Duration.ofHours(10), Duration.ofMinutes(30), false);    // expired but recent
        seed(TokenPurpose.EMAIL_VERIFICATION, Duration.ofHours(1), Duration.ofHours(24), false);    // fresh

        assertThat(accountService.purgeStaleTokens()).isZero();
        assertThat(tokens.count()).isEqualTo(3);
    }

    /** Persist a token aged {@code age} ago with the given TTL, optionally marked used. */
    private UUID seed(TokenPurpose purpose, Duration age, Duration ttl, boolean used) {
        Instant created = Instant.now().minus(age);
        AccountToken token = AccountToken.issue(accountId, purpose,
                Hashes.sha256Hex(UUID.randomUUID().toString()), created.plus(ttl), created);
        tokens.save(token);
        if (used) {
            jdbc.update("UPDATE account_tokens SET used_at = ? WHERE id = ?",
                    java.sql.Timestamp.from(created.plusSeconds(1)), token.getId());
        }
        return token.getId();
    }
}
