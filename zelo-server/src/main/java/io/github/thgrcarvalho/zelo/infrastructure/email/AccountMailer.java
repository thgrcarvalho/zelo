package io.github.thgrcarvalho.zelo.infrastructure.email;

import io.github.thgrcarvalho.zelo.application.email.AccountEmailRequested;
import io.github.thgrcarvalho.zelo.application.email.EmailChangedNotice;
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
            case EMAIL_CHANGE -> sendEmailChange(event.email(), event.rawToken());
            // Statement switch isn't exhaustiveness-checked: fail loud if a new purpose
            // is added without a template here, rather than silently dropping its email.
            default -> throw new IllegalStateException("No email template for purpose " + event.purpose());
        }
    }

    /**
     * Heads-up to the OLD address after a completed email change. Careful with the
     * advice: by the time this sends, the swap is committed, so "Forgot password?"
     * with this (old) address is a silent no-op — the two recourses that actually
     * work are replying to support and a still-open signed-in dashboard session.
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onEmailChangedNotice(EmailChangedNotice notice) {
        send(notice.oldEmail(), "Your Zelo account email was changed",
                "The login email for your Zelo account was just changed to "
                        + notice.newEmail() + ".\n\n"
                        + "If you made this change, no action is needed.\n\n"
                        + "If you did NOT make this change, your password is compromised. "
                        + "REPLY TO THIS EMAIL immediately so we can freeze and recover the "
                        + "account — a password reset with this (old) address no longer works, "
                        + "because it is no longer the account's login. If you still have the "
                        + "dashboard open and signed in, you can also change the email straight "
                        + "back yourself: " + links.appUrl());
    }

    private void sendVerification(String to, String rawToken) {
        send(to, "Verify your Zelo email",
                "Welcome to Zelo.\n\n"
                        + "Confirm this email address to activate your account and start issuing API keys:\n\n"
                        + links.verifyUrl(rawToken) + "\n\n"
                        + "This link expires soon. If you didn't sign up for Zelo, you can ignore this email.");
    }

    private void sendEmailChange(String to, String rawToken) {
        send(to, "Confirm your new Zelo email",
                "A request was made to move a Zelo account to this email address.\n\n"
                        + "Confirm it here:\n\n"
                        + links.emailChangeUrl(rawToken) + "\n\n"
                        + "This link expires soon and nothing changes until you confirm. "
                        + "If you don't recognize this, just ignore this email.");
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
