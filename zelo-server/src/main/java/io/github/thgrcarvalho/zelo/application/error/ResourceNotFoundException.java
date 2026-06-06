package io.github.thgrcarvalho.zelo.application.error;

/** Thrown when a referenced resource (subject, purpose, request) does not exist. */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }
}
