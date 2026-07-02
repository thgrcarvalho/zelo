package io.github.thgrcarvalho.zelo.domain.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A single-use, hashed, purpose-bound token backing email verification and
 * password reset. The raw token is generated once, handed to the integrator in an
 * email link, and never persisted — only its SHA-256 hex hash is stored (same
 * discipline as API keys). Single-use is enforced by {@code usedAt}: the redeem
 * path flips it via an atomic {@code UPDATE ... WHERE used_at IS NULL}, so a
 * double-click or replay can never redeem twice.
 */
@Entity
@Table(name = "account_tokens")
public class AccountToken {

    @Id
    private UUID id;

    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false, updatable = false)
    private TokenPurpose purpose;

    @Column(name = "token_hash", nullable = false, unique = true, updatable = false)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** The pending address an {@code EMAIL_CHANGE} token confirms; null for other purposes. */
    @Column(name = "new_email", updatable = false)
    private String newEmail;

    protected AccountToken() {
        // for JPA
    }

    private AccountToken(UUID id, UUID accountId, TokenPurpose purpose, String tokenHash,
                         Instant expiresAt, Instant createdAt, String newEmail) {
        this.id = id;
        this.accountId = accountId;
        this.purpose = purpose;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.createdAt = createdAt;
        this.newEmail = newEmail;
    }

    /** Issue a fresh token for {@code accountId} and {@code purpose}, storing only the hash. */
    public static AccountToken issue(UUID accountId, TokenPurpose purpose, String tokenHash,
                                     Instant expiresAt, Instant createdAt) {
        return new AccountToken(UUID.randomUUID(), accountId, purpose, tokenHash, expiresAt, createdAt, null);
    }

    /** Issue an {@code EMAIL_CHANGE} token carrying the pending new address. */
    public static AccountToken issueEmailChange(UUID accountId, String tokenHash,
                                                Instant expiresAt, Instant createdAt, String newEmail) {
        return new AccountToken(UUID.randomUUID(), accountId, TokenPurpose.EMAIL_CHANGE,
                tokenHash, expiresAt, createdAt, newEmail);
    }

    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    public boolean isUsed() {
        return usedAt != null;
    }

    public UUID getId() {
        return id;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public TokenPurpose getPurpose() {
        return purpose;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getUsedAt() {
        return usedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getNewEmail() {
        return newEmail;
    }
}
