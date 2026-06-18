package io.github.thgrcarvalho.zelo.application.email;

import io.github.thgrcarvalho.zelo.domain.account.TokenPurpose;

/**
 * Domain event published inside the account transaction when a verification or
 * password-reset email should go out. An {@code AFTER_COMMIT} listener sends it, so
 * the email is dispatched only once the token row is durably committed — never
 * inside the transaction, and never for a write that ends up rolling back. The raw
 * token rides the event (it lives only in the email link, never persisted); the
 * {@code purpose} selects which template the listener renders.
 */
public record AccountEmailRequested(String email, String rawToken, TokenPurpose purpose) {

    /** Redact the raw token — it's a live credential and must never reach a log line. */
    @Override
    public String toString() {
        return "AccountEmailRequested[email=" + email + ", purpose=" + purpose + ", rawToken=***]";
    }
}
