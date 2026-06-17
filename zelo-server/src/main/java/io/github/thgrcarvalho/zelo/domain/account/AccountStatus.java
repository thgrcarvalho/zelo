package io.github.thgrcarvalho.zelo.domain.account;

/**
 * Lifecycle of an account. Signup creates it {@code PENDING}; an operator moves it
 * to {@code ACTIVE} (may then mint keys) or {@code REJECTED}. Only {@code ACTIVE}
 * accounts can issue or manage API keys. Stored as the enum name in a
 * {@code VARCHAR} column.
 */
public enum AccountStatus {
    PENDING,
    ACTIVE,
    REJECTED
}
