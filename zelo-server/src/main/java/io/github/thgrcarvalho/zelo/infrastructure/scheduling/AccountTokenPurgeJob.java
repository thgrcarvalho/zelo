package io.github.thgrcarvalho.zelo.infrastructure.scheduling;

import io.github.thgrcarvalho.zelo.application.AccountService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically purges spent {@code account_tokens} rows so the table — which the
 * verify/reset path only ever appends to — can't grow without bound and slow the
 * cooldown/cap scans. The interval is {@code zelo.mail.token-purge-interval-ms}
 * (default 1h). Single-instance deployment, so a plain {@code @Scheduled} is enough;
 * a horizontal scale-out would need a shared lock to avoid concurrent runs.
 *
 * <p>Purge is best-effort housekeeping: a failure (a DB blip, a lock timeout) is
 * caught and logged rather than propagated, so it never poisons the shared
 * scheduler or stalls the neighbouring overdue sweep — the next tick just retries.</p>
 */
@Component
public class AccountTokenPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(AccountTokenPurgeJob.class);

    private final AccountService accounts;

    public AccountTokenPurgeJob(AccountService accounts) {
        this.accounts = accounts;
    }

    @Scheduled(
            initialDelayString = "${zelo.mail.token-purge-interval-ms:3600000}",
            fixedDelayString = "${zelo.mail.token-purge-interval-ms:3600000}")
    public void run() {
        try {
            accounts.purgeStaleTokens();
        } catch (RuntimeException e) {
            log.warn("Stale-token purge failed; will retry next cycle", e);
        }
    }
}
