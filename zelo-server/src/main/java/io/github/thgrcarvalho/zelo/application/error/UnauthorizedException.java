package io.github.thgrcarvalho.zelo.application.error;

/**
 * Thrown when a request lacks a valid session/credential (401). Used by the
 * {@code /account} surface, where authentication is enforced at the controller
 * boundary (argument resolver / service) rather than by a filter writing the
 * response directly.
 */
public class UnauthorizedException extends RuntimeException {

    public UnauthorizedException(String message) {
        super(message);
    }
}
