package io.github.thgrcarvalho.zelo.application;

import io.github.thgrcarvalho.zelo.domain.account.Account;
import io.github.thgrcarvalho.zelo.domain.account.AccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Provider-neutral plan transitions driven by billing webhooks. Every method is
 * idempotent (sets state, never increments), so provider redeliveries are safe
 * without a dedup table — the same property Vitalio's integration relies on.
 *
 * <p>Downgrades are scoped to the subscription id the account currently tracks,
 * so a stale event from an abandoned earlier subscription can't cancel a newer
 * paid one. Activation with no tracked subscription adopts the event's ids
 * (webhook can land before the checkout response is persisted).</p>
 */
@Service
public class BillingEventService {

    private static final Logger log = LoggerFactory.getLogger(BillingEventService.class);

    private final AccountRepository accounts;

    public BillingEventService(AccountRepository accounts) {
        this.accounts = accounts;
    }

    @Transactional
    public void paymentConfirmed(UUID accountId, String customerId, String subscriptionId) {
        Account account = load(accountId, "payment-confirmed");
        if (account == null) {
            return;
        }
        if (account.getBillingSubscriptionId() == null && subscriptionId != null) {
            account.attachBilling(
                    account.getBillingCustomerId() == null ? customerId : account.getBillingCustomerId(),
                    subscriptionId);
        }
        account.activatePro();
        log.info("Billing: account {} is PRO/ACTIVE", accountId);
    }

    @Transactional
    public void paymentOverdue(UUID accountId) {
        Account account = load(accountId, "payment-overdue");
        if (account == null) {
            return;
        }
        account.markPaymentOverdue();
        log.info("Billing: account {} marked OVERDUE", accountId);
    }

    @Transactional
    public void subscriptionEnded(UUID accountId, String subscriptionId) {
        Account account = load(accountId, "subscription-ended");
        if (account == null) {
            return;
        }
        // Scope the downgrade: only the currently-tracked subscription may cancel.
        if (account.getBillingSubscriptionId() != null && subscriptionId != null
                && !account.getBillingSubscriptionId().equals(subscriptionId)) {
            log.warn("Billing: ignoring end-event for stale subscription {} on account {} (tracking {})",
                    subscriptionId, accountId, account.getBillingSubscriptionId());
            return;
        }
        account.cancelToFree();
        log.info("Billing: account {} downgraded to FREE (subscription ended)", accountId);
    }

    private Account load(UUID accountId, String event) {
        Account account = accounts.findById(accountId).orElse(null);
        if (account == null) {
            // Money may have moved with no entitlement to grant — loud, not silent.
            log.error("Billing: {} event for unknown account {}", event, accountId);
        }
        return account;
    }
}
