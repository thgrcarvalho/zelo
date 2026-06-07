package io.github.thgrcarvalho.zelo.starter;

/** Raised when a webhook arrives for an event type with no {@link ZeloWebhook} handler. */
public class NoHandlerRegisteredException extends RuntimeException {

    public NoHandlerRegisteredException(String eventType) {
        super("No @ZeloWebhook handler registered for event '" + eventType + "'");
    }
}
