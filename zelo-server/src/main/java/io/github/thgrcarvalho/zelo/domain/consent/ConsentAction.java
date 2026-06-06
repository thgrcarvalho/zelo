package io.github.thgrcarvalho.zelo.domain.consent;

/**
 * A consent ledger action. Maps to the {@code consent_action} Postgres enum.
 */
public enum ConsentAction {
    GRANT,
    WITHDRAW
}
