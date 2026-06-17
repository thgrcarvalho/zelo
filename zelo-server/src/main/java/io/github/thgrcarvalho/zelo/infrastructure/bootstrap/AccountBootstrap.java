package io.github.thgrcarvalho.zelo.infrastructure.bootstrap;

import io.github.thgrcarvalho.zelo.domain.account.Account;
import io.github.thgrcarvalho.zelo.domain.account.AccountRepository;
import io.github.thgrcarvalho.zelo.domain.crypto.Passwords;
import io.github.thgrcarvalho.zelo.infrastructure.config.ZeloProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * Seeds the first operator account from {@code zelo.auth.operator-*} on startup, so
 * a fresh deploy has someone who can work the approval queue without a chicken-and-
 * egg signup. Idempotent: an existing account with that email is promoted to an
 * active operator if it isn't one already (so the deploy never ends up with no
 * operator), but its password is never reset. Mirrors {@link ApiKeyBootstrap}.
 */
@Component
public class AccountBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AccountBootstrap.class);

    /** The operator password must meet the same floor enforced on self-service signup. */
    private static final int MIN_PASSWORD_LENGTH = 8;

    private final AccountRepository accounts;
    private final ZeloProperties properties;

    public AccountBootstrap(AccountRepository accounts, ZeloProperties properties) {
        this.accounts = accounts;
        this.properties = properties;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        ZeloProperties.Auth config = properties.getAuth();
        if (isBlank(config.getOperatorEmail())) {
            return;
        }
        if (isBlank(config.getOperatorPassword())) {
            log.warn("zelo.auth.operator-email is set but operator-password is blank; skipping operator seed");
            return;
        }
        if (config.getOperatorPassword().length() < MIN_PASSWORD_LENGTH) {
            log.error("zelo.auth.operator-password is shorter than {} chars; refusing to seed a weak operator account",
                    MIN_PASSWORD_LENGTH);
            return;
        }
        String email = config.getOperatorEmail().trim().toLowerCase(Locale.ROOT);
        accounts.findByEmail(email).ifPresentOrElse(
                existing -> {
                    if (existing.isOperator() && existing.isActive()) {
                        log.info("Operator account '{}' already present; leaving it untouched", email);
                    } else {
                        // Don't silently leave the deploy with no usable operator: the
                        // configured email explicitly names the operator, so promote it.
                        existing.makeOperator();
                        accounts.save(existing);
                        log.warn("Account '{}' promoted to ACTIVE operator per zelo.auth.operator-email", email);
                    }
                },
                () -> {
                    accounts.save(Account.operator(UUID.randomUUID(), email,
                            Passwords.hash(config.getOperatorPassword()), "Zelo Operator", Instant.now()));
                    log.info("Seeded operator account '{}'", email);
                });
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
