package io.github.thgrcarvalho.zelo.infrastructure.email;

import io.github.thgrcarvalho.zelo.application.email.EmailSender;
import io.github.thgrcarvalho.zelo.infrastructure.config.ZeloProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Wires the email layer. {@link EnableAsync} powers {@link AccountMailer}'s off-thread
 * sends. The sender is config-selected, never profile-selected:
 * {@code zelo.mail.enabled=true} gives the SMTP adapter; otherwise the logging no-op
 * (so dev/CI never send real mail and unconfigured prod fails closed).
 */
@Configuration
@EnableAsync
public class EmailConfig {

    private static final Logger log = LoggerFactory.getLogger(EmailConfig.class);

    @Bean
    @ConditionalOnProperty(name = "zelo.mail.enabled", havingValue = "true")
    EmailSender smtpEmailSender(JavaMailSender mailSender, ZeloProperties properties,
                                @Value("${spring.mail.password:}") String smtpPassword,
                                @Value("${spring.mail.properties.mail.smtp.auth:false}") boolean smtpAuth) {
        ZeloProperties.Mail mail = properties.getMail();
        if (mail.getFrom() == null || mail.getFrom().isBlank()) {
            throw new IllegalStateException("zelo.mail.from is required when zelo.mail.enabled=true");
        }
        if (mail.getBaseUrl() == null || mail.getBaseUrl().isBlank()) {
            throw new IllegalStateException("zelo.mail.base-url is required when zelo.mail.enabled=true");
        }
        return new SmtpEmailSender(mailSender, mail.getFrom(), mail.getReplyTo(), smtpAuth, smtpPassword);
    }

    @Bean
    @ConditionalOnMissingBean(EmailSender.class)
    EmailSender loggingEmailSender(ZeloProperties properties) {
        if (properties.getMail().isRequireVerification()) {
            // Fail-closed but otherwise silent: surface WHY signup will 503 so an
            // operator isn't left guessing at a fresh deploy.
            log.warn("Email is disabled (zelo.mail.enabled!=true) but zelo.mail.require-verification=true — "
                    + "signup/resend/password-reset will return 503. Set ZELO_MAIL_ENABLED=true with "
                    + "ZELO_MAIL_FROM, ZELO_MAIL_BASE_URL and the SMTP password to enable onboarding.");
        }
        return new LoggingEmailSender();
    }

    /** Validated at startup (https, host, no path) — see {@link EmailLinks}. */
    @Bean
    EmailLinks emailLinks(ZeloProperties properties) {
        return new EmailLinks(properties.getMail().getBaseUrl());
    }
}
