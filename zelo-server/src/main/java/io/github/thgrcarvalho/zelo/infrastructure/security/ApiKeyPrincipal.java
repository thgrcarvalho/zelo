package io.github.thgrcarvalho.zelo.infrastructure.security;

import java.util.UUID;

/**
 * The authenticated integrator for the current request, resolved from the API key.
 * The webhook destination/secret are loaded directly by the (poller-thread) webhook
 * dispatcher, which has no request context, so they are deliberately not carried here.
 */
public record ApiKeyPrincipal(
        UUID id,
        String name
) {
}
