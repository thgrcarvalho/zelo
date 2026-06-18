package io.github.thgrcarvalho.zelo.application.email;

/**
 * Port for sending transactional email. Two adapters back it: an SMTP sender (prod,
 * when {@code zelo.mail.enabled=true}) and a logging no-op (dev/CI). Mirrors the
 * {@code SessionTokens} "one bean, configured-or-not, fail-closed" shape:
 * {@link #isConfigured()} lets callers refuse to proceed (e.g. signup → 503) rather
 * than silently skip a required verification email.
 */
public interface EmailSender {

    /** Whether real email can be sent (the SMTP adapter with a non-blank from-address). */
    boolean isConfigured();

    /** Send one message. May throw {@code EmailDeliveryException} on transport failure. */
    void send(EmailMessage message);
}
