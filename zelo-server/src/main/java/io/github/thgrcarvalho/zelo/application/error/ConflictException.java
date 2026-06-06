package io.github.thgrcarvalho.zelo.application.error;

/** Thrown when an operation conflicts with existing state (e.g. duplicate purpose). */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
