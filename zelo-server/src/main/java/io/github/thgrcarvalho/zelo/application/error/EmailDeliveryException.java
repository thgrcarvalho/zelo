package io.github.thgrcarvalho.zelo.application.error;

/**
 * A transactional email failed to hand off to the transport. Thrown by the SMTP
 * sender; caught by the async mailer (the user is never blocked on delivery and can
 * always retry via resend / reset-request), so it does not surface as an HTTP error.
 */
public class EmailDeliveryException extends RuntimeException {

    public EmailDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
