package io.github.thgrcarvalho.zelo.infrastructure.email;

import io.github.thgrcarvalho.zelo.application.email.AccountEmailRequested;
import io.github.thgrcarvalho.zelo.application.email.EmailMessage;
import io.github.thgrcarvalho.zelo.application.email.EmailSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Sends the account lifecycle emails in response to an {@link AccountEmailRequested}
 * event. Bound to {@code AFTER_COMMIT} so a verify/reset link never points at a token
 * row that rolled back, and {@code @Async} so SMTP latency stays off the request
 * thread (an unauthenticated caller can't time "did this email exist?" from the
 * response). A delivery failure is logged, not surfaced — the user always has a retry
 * path (resend / reset-request), and after commit there is nothing left to fail.
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
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAccountEmailRequested(AccountEmailRequested event) {
        switch (event.purpose()) {
            case EMAIL_VERIFICATION -> sendVerification(event.email(), event.rawToken());
            case PASSWORD_RESET -> sendPasswordReset(event.email(), event.rawToken());
            // Statement switch isn't exhaustiveness-checked: fail loud if a new purpose
            // is added without a template here, rather than silently dropping its email.
            default -> throw new IllegalStateException("No email template for purpose " + event.purpose());
        }
    }

    private void sendVerification(String to, String rawToken) {
        send(to, "Verify your Zelo email",
                "Welcome to Zelo.\n\n"
                        + "Confirm this email address to activate your account and start issuing API keys:\n\n"
                        + links.verifyUrl(rawToken) + "\n\n"
                        + "This link expires soon. If you didn't sign up for Zelo, you can ignore this email.");
    }

    private void sendPasswordReset(String to, String rawToken) {
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
