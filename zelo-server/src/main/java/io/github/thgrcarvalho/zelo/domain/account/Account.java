package io.github.thgrcarvalho.zelo.domain.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * A self-service integrator account: the human-facing owner of one or more API
 * keys. Holds B2B contact data (email + org), never end-user PII.
 *
 * <p>Onboarding is instant + email-gated: an account is created {@code UNVERIFIED}
 * and becomes {@code ACTIVE} when the integrator clicks the emailed verification
 * link — no operator approval. Only {@code ACTIVE} accounts mint keys.</p>
 *
 * <p>{@code passwordChangedAt} is a monotonic watermark: every session token is
 * minted carrying the account's current value, and a password change advances it,
 * which invalidates all previously-issued sessions (see {@code SessionTokens} /
 * {@code SessionAuthFilter}). Truncated to milliseconds and forced strictly
 * increasing so two distinct password states never share a watermark.</p>
 *
 * <p>Mutable aggregate → JPA. {@code status} is stored as the enum name in a
 * {@code VARCHAR} column (no native Postgres enum), matching the V8/V9 schema.</p>
 */
@Entity
@Table(name = "accounts")
public class Account {

    @Id
    private UUID id;

    @Column(name = "email", nullable = false, unique = true, updatable = false)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "org_name", nullable = false)
    private String orgName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private AccountStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "plan", nullable = false)
    private Plan plan = Plan.FREE;

    /** Billing lifecycle slot; stays NONE until a payment provider drives it. */
    @Enumerated(EnumType.STRING)
    @Column(name = "plan_status", nullable = false)
    private PlanStatus planStatus = PlanStatus.NONE;

    /** Optimistic-lock guard: a concurrent second verify/password-change loses with an optimistic-lock failure. */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** When the email was verified (UNVERIFIED → ACTIVE); null while unverified. */
    @Column(name = "email_verified_at")
    private Instant emailVerifiedAt;

    /** Monotonic watermark baked into session tokens; advances on every password change. */
    @Column(name = "password_changed_at", nullable = false)
    private Instant passwordChangedAt;

    /** Consecutive failed logins; reset on a successful login or a password reset. Maintained via direct UPDATE. */
    @Column(name = "failed_login_count", nullable = false)
    private int failedLoginCount;

    /** When set and in the future, logins are rejected (429) regardless of the password supplied. */
    @Column(name = "locked_until")
    private Instant lockedUntil;

    protected Account() {
        // for JPA
    }

    private Account(UUID id, String email, String passwordHash, String orgName,
                    AccountStatus status, Instant createdAt) {
        this.id = id;
        this.email = email;
        this.passwordHash = passwordHash;
        this.orgName = orgName;
        this.status = status;
        this.createdAt = createdAt;
        this.passwordChangedAt = createdAt.truncatedTo(ChronoUnit.MILLIS);
    }

    /** A freshly signed-up integrator: a {@code UNVERIFIED} account awaiting email verification. */
    public static Account signup(UUID id, String email, String passwordHash, String orgName, Instant createdAt) {
        return new Account(id, email, passwordHash, orgName, AccountStatus.UNVERIFIED, createdAt);
    }

    /**
     * UNVERIFIED → ACTIVE, recording when. Idempotent guard: only an unverified
     * account transitions; calling on an already-verified account is a no-op.
     */
    public void markEmailVerified(Instant when) {
        if (status == AccountStatus.UNVERIFIED) {
            this.status = AccountStatus.ACTIVE;
            this.emailVerifiedAt = when;
        }
    }

    /**
     * Set a new password hash and advance the watermark so every previously-issued
     * session is invalidated. The watermark is kept strictly increasing (truncated
     * to ms, bumped past the previous value on a tie) so no two password states
     * share a value — the session check is exact equality.
     */
    public void changePassword(String newPasswordHash, Instant when) {
        this.passwordHash = newPasswordHash;
        Instant candidate = when.truncatedTo(ChronoUnit.MILLIS);
        if (!candidate.isAfter(this.passwordChangedAt)) {
            candidate = this.passwordChangedAt.plusMillis(1);
        }
        this.passwordChangedAt = candidate;
    }

    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getOrgName() {
        return orgName;
    }

    public AccountStatus getStatus() {
        return status;
    }

    public Plan getPlan() {
        return plan;
    }

    public PlanStatus getPlanStatus() {
        return planStatus;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getEmailVerifiedAt() {
        return emailVerifiedAt;
    }

    public Instant getPasswordChangedAt() {
        return passwordChangedAt;
    }

    public boolean isActive() {
        return status == AccountStatus.ACTIVE;
    }

    public boolean isVerified() {
        return emailVerifiedAt != null;
    }

    public int getFailedLoginCount() {
        return failedLoginCount;
    }

    /** True while a brute-force lockout is in effect. */
    public boolean isLocked(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }
}
