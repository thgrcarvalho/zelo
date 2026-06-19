package io.github.thgrcarvalho.zelo.infrastructure.security;

import io.github.thgrcarvalho.zelo.domain.account.AccountStatus;

import java.util.UUID;

/**
 * The authenticated account for the current {@code /account} request, resolved from
 * the session cookie by {@link SessionAuthFilter}. Status is loaded fresh from the
 * database on every request, so verification takes effect immediately — the session
 * token itself carries only the account id and a password watermark.
 */
public record AccountPrincipal(
        UUID id,
        AccountStatus status
) {
    public boolean isActive() {
        return status == AccountStatus.ACTIVE;
    }
}
