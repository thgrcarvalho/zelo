package io.github.thgrcarvalho.zelo.infrastructure.security;

import io.github.thgrcarvalho.zelo.domain.account.AccountRole;
import io.github.thgrcarvalho.zelo.domain.account.AccountStatus;

import java.util.UUID;

/**
 * The authenticated account for the current {@code /account} request, resolved
 * from the session cookie by {@link SessionAuthFilter}. Role and status are loaded
 * fresh from the database on every request, so an approval or role change takes
 * effect immediately — the session token itself carries only the account id.
 */
public record AccountPrincipal(
        UUID id,
        AccountRole role,
        AccountStatus status
) {
    public boolean isActive() {
        return status == AccountStatus.ACTIVE;
    }

    public boolean isOperator() {
        return role == AccountRole.OPERATOR;
    }
}
