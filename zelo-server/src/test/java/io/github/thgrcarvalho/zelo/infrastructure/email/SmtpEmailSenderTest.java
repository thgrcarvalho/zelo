package io.github.thgrcarvalho.zelo.infrastructure.email;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SmtpEmailSenderTest {

    private static SmtpEmailSender sender(String from, boolean authRequired, String password) {
        // JavaMailSender is only touched by send(), not isConfigured().
        return new SmtpEmailSender(null, from, null, authRequired, password);
    }

    @Test
    void requiresAFromAddress() {
        assertThat(sender("", false, "").isConfigured()).isFalse();
        assertThat(sender(null, true, "secret").isConfigured()).isFalse();
    }

    @Test
    void withoutSmtpAuthAPasswordIsNotNeeded() {
        // e.g. a local / in-process SMTP (GreenMail) — a blank password is fine.
        assertThat(sender("no-reply@zelo.test", false, "").isConfigured()).isTrue();
    }

    @Test
    void withSmtpAuthABlankPasswordFailsClosed() {
        // The prod shape (Resend): auth on but the API key missing → not configured,
        // so signup 503s instead of 202-ing accounts whose mail silently bounces.
        assertThat(sender("no-reply@zelo.test", true, "").isConfigured()).isFalse();
        assertThat(sender("no-reply@zelo.test", true, null).isConfigured()).isFalse();
        assertThat(sender("no-reply@zelo.test", true, "re_apikey").isConfigured()).isTrue();
    }
}
