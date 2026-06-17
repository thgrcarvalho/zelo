package io.github.thgrcarvalho.zelo.domain.account;

/**
 * Who an account is. A {@code USER} is an integrator that manages its own keys; an
 * {@code OPERATOR} additionally works the approval queue (approve/reject pending
 * signups). Stored as the enum name in a {@code VARCHAR} column.
 */
public enum AccountRole {
    USER,
    OPERATOR
}
