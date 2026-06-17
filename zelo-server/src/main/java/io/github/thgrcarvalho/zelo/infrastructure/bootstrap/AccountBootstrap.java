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
 * egg signup. Idempotent: if an account with that email already exists it is left
 * untouched (the password is not reset). Mirrors {@link ApiKeyBootstrap}.
 */
@Component
public class AccountBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AccountBootstrap.class);

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
        String email = config.getOperatorEmail().trim().toLowerCase(Locale.ROOT);
        accounts.findByEmail(email).ifPresentOrElse(
                existing -> log.info("Operator account '{}' already present; leaving it untouched", email),
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
