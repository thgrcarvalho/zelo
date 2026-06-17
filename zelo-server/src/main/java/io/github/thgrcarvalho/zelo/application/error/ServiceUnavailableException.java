package io.github.thgrcarvalho.zelo.application.error;

/**
 * Thrown when a feature is disabled by configuration and so cannot serve the
 * request (503): e.g. the {@code /account} surface when {@code zelo.auth.session-secret}
 * is blank, where signup/login would otherwise fail with an opaque 500.
 */
public class ServiceUnavailableException extends RuntimeException {

    public ServiceUnavailableException(String message) {
        super(message);
    }
}
