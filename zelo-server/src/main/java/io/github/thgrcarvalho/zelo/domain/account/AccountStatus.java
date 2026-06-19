package io.github.thgrcarvalho.zelo.domain.account;

/**
 * Lifecycle of a self-service account. Signup creates it {@code UNVERIFIED}
 * (logged-out — it cannot mint keys); clicking the emailed verification link moves
 * it to {@code ACTIVE}, after which it self-issues and manages API keys. There is
 * no operator/approval step — email verification is the only gate. Stored as the
 * enum name in a {@code VARCHAR} column.
 */
public enum AccountStatus {
    /** Signed up but has not yet verified its email; inert (no key access). */
    UNVERIFIED,
    /** Email verified; may self-issue and manage API keys. */
    ACTIVE
}
