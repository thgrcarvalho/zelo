package io.github.thgrcarvalho.zelo.domain.dsr;

/** Thrown when a DSR is asked to make a transition its current status forbids. */
public class InvalidDsrTransitionException extends RuntimeException {

    public InvalidDsrTransitionException(String message) {
        super(message);
    }
}
