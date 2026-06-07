package io.github.thgrcarvalho.zelo.demo;

/**
 * A user record in the demo integrator's own store. This is where the PII lives —
 * Zelo never sees {@code name}/{@code email}, only the opaque {@code externalId}.
 */
public record DemoUser(String externalId, String name, String email) {
}
