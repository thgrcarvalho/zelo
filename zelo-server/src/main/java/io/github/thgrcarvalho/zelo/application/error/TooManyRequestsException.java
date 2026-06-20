package io.github.thgrcarvalho.zelo.application.error;

/**
 * Thrown when a client is being throttled (429) — e.g. a temporarily locked account
 * after too many failed logins. The message is safe to surface to the caller.
 */
public class TooManyRequestsException extends RuntimeException {

    public TooManyRequestsException(String message) {
        super(message);
    }
}
