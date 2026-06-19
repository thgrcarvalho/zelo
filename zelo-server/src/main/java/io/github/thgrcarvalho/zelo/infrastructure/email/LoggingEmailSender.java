package io.github.thgrcarvalho.zelo.infrastructure.email;

import io.github.thgrcarvalho.zelo.application.email.EmailMessage;
import io.github.thgrcarvalho.zelo.application.email.EmailSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The no-op fallback used when {@code zelo.mail.enabled} is not true (local dev,
 * CI). {@link #isConfigured()} returns false, so callers that require email
 * (signup with verification on) fail closed instead of silently activating
 * unverified accounts. Logs the recipient + subject only — never the body, which
 * carries the token link.
 */
public class LoggingEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailSender.class);

    @Override
    public boolean isConfigured() {
        return false;
    }

    @Override
    public void send(EmailMessage message) {
        log.warn("Email NOT sent (mail disabled): to={}, subject='{}'", message.to(), message.subject());
    }
}
