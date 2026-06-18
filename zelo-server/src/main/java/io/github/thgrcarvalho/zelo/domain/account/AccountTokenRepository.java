package io.github.thgrcarvalho.zelo.domain.account;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface AccountTokenRepository extends JpaRepository<AccountToken, UUID> {

    Optional<AccountToken> findByTokenHash(String tokenHash);

    /** Most recent token for an (account, purpose) — backs the resend/request cooldown. */
    Optional<AccountToken> findFirstByAccountIdAndPurposeOrderByCreatedAtDesc(UUID accountId, TokenPurpose purpose);

    /** How many tokens of a purpose an account has minted since {@code since} — backs the daily cap. */
    int countByAccountIdAndPurposeAndCreatedAtAfter(UUID accountId, TokenPurpose purpose, Instant since);

    /**
     * Atomically mark a token used, but only if it is still unredeemed. Returns the
     * number of rows changed (1 = this caller won the single-use race, 0 = already
     * used / gone). This is the single-use enforcement point.
     */
    @Modifying
    @Query("update AccountToken t set t.usedAt = :when where t.id = :id and t.usedAt is null")
    int consume(@Param("id") UUID id, @Param("when") Instant when);

    /**
     * Invalidate every still-unredeemed token of a purpose for an account (used on
     * resend/re-request so only the newest link works). Sets used_at, so a
     * superseded token then fails the {@link #consume} guard → uniform "invalid".
     */
    @Modifying
    @Query("update AccountToken t set t.usedAt = :when "
            + "where t.accountId = :accountId and t.purpose = :purpose and t.usedAt is null")
    int invalidateOutstanding(@Param("accountId") UUID accountId,
                              @Param("purpose") TokenPurpose purpose,
                              @Param("when") Instant when);

    /**
     * Bulk-delete stale token rows — the table is append-only on the hot path, so
     * without this it grows unbounded and the cooldown/cap scans slowly degrade. A
     * row is safe to drop only when it is <em>both</em>:
     * <ul>
     *   <li>expired ({@code expires_at <= now}) — so it can never be redeemed,
     *       regardless of how long its TTL was, and</li>
     *   <li>created before {@code windowCutoff} — past the cooldown + daily-cap
     *       windows, so deleting it can't let an account exceed either limit.</li>
     * </ul>
     * Both clauses are independently necessary (an expired-but-recent reset token
     * still counts toward the daily cap; an old-but-unexpired verify token is still
     * a live link). The {@code <=} on expiry matches {@link AccountToken#isExpired}
     * ({@code now >= expiresAt}), so the purge's notion of "expired" is exactly the
     * redeem path's. Returns the number of rows deleted.
     */
    @Modifying
    @Query("delete from AccountToken t where t.expiresAt <= :now and t.createdAt < :windowCutoff")
    int deleteStale(@Param("now") Instant now, @Param("windowCutoff") Instant windowCutoff);
}
