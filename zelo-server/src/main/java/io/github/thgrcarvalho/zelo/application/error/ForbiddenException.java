package io.github.thgrcarvalho.zelo.application.error;

/**
 * Thrown when an authenticated principal lacks the right to an operation (403):
 * e.g. a non-ACTIVE account trying to mint a key, or a non-OPERATOR account
 * working the approval queue.
 */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}
