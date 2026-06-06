package io.github.thgrcarvalho.zelo.infrastructure.security;

import java.util.UUID;

/**
 * The authenticated integrator for the current request, resolved from the API key.
 * Carries the webhook destination/secret so the dispatcher (M3) needs no extra lookup.
 */
public record ApiKeyPrincipal(
        UUID id,
        String name,
        String webhookUrl,
        String webhookSecret
) {
}
