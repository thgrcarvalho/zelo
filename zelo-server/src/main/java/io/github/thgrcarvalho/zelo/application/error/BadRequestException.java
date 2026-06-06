package io.github.thgrcarvalho.zelo.application.error;

/** Thrown for malformed client input that bean validation does not cover. */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }
}
