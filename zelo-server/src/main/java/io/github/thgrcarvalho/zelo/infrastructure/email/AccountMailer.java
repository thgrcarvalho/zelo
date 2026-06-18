package io.github.thgrcarvalho.zelo.infrastructure.email;

import io.github.thgrcarvalho.zelo.application.email.EmailMessage;
import io.github.thgrcarvalho.zelo.application.email.EmailSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Builds and sends the account lifecycle emails — asynchronously, off the request
 * thread. Async is deliberate: it keeps SMTP latency out of the HTTP response (so
 * an unauthenticated caller can't time "did this email exist?" from how long the
 * response took), and it runs after the controller's transaction has committed, so
 * a verify link never points at a token row that rolled back. A delivery failure is
 * logged, not surfaced — the user always has a retry path (resend / reset-request).
 */
@Component
public class AccountMailer {

    private static final Logger log = LoggerFactory.getLogger(AccountMailer.class);

    private final EmailSender sender;
    private final EmailLinks links;

    public AccountMailer(EmailSender sender, EmailLinks links) {
        this.sender = sender;
        this.links = links;
    }

    @Async
    public void sendVerification(String to, String rawToken) {
        send(to, "Verify your Zelo email",
                "Welcome to Zelo.\n\n"
                        + "Confirm this email address to activate your account and start issuing API keys:\n\n"
                        + links.verifyUrl(rawToken) + "\n\n"
                        + "This link expires soon. If you didn't sign up for Zelo, you can ignore this email.");
    }

    @Async
    public void sendPasswordReset(String to, String rawToken) {
        send(to, "Reset your Zelo password",
                "We received a request to reset the password for your Zelo account.\n\n"
                        + "Choose a new password here:\n\n"
                        + links.resetUrl(rawToken) + "\n\n"
                        + "This link expires soon. If you didn't request this, ignore this email "
                        + "— your password hasn't changed.");
    }

    private void send(String to, String subject, String body) {
        try {
            sender.send(new EmailMessage(to, subject, body));
        } catch (RuntimeException e) {
            log.error("Failed to send '{}' email to {}", subject, to, e);
        }
    }
}
