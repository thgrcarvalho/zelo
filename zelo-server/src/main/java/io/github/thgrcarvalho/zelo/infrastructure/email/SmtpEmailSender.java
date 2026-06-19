package io.github.thgrcarvalho.zelo.infrastructure.email;

import io.github.thgrcarvalho.zelo.application.email.EmailMessage;
import io.github.thgrcarvalho.zelo.application.email.EmailSender;
import io.github.thgrcarvalho.zelo.application.error.EmailDeliveryException;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * Sends plain-text email through the auto-configured {@link JavaMailSender} (an SMTP
 * relay — Resend by default, see {@code application.yml}). Active only when
 * {@code zelo.mail.enabled=true}. {@link #isConfigured()} additionally requires a
 * non-blank from-address AND — when SMTP auth is on — a non-blank password, so a
 * deploy that enables mail but forgets the Resend API key fails CLOSED (signup 503)
 * rather than 202-ing accounts whose verification email silently bounces. (When auth
 * is off, e.g. a local/in-process SMTP, no password is needed.)
 */
public class SmtpEmailSender implements EmailSender {

    private final JavaMailSender mailSender;
    private final String from;
    private final String replyTo;
    private final boolean authRequired;
    private final String password;

    public SmtpEmailSender(JavaMailSender mailSender, String from, String replyTo,
                           boolean authRequired, String password) {
        this.mailSender = mailSender;
        this.from = from;
        this.replyTo = replyTo;
        this.authRequired = authRequired;
        this.password = password;
    }

    @Override
    public boolean isConfigured() {
        if (from == null || from.isBlank()) {
            return false;
        }
        return !authRequired || (password != null && !password.isBlank());
    }

    @Override
    public void send(EmailMessage message) {
        SimpleMailMessage mail = new SimpleMailMessage();
        mail.setFrom(from);
        if (replyTo != null && !replyTo.isBlank()) {
            mail.setReplyTo(replyTo);
        }
        mail.setTo(message.to());
        mail.setSubject(message.subject());
        mail.setText(message.body());
        try {
            mailSender.send(mail);
        } catch (MailException e) {
            // Don't echo the body (it carries the token link) into the exception.
            throw new EmailDeliveryException("Failed to send email to " + message.to(), e);
        }
    }
}
