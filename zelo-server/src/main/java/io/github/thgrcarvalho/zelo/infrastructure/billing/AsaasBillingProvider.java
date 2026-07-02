package io.github.thgrcarvalho.zelo.infrastructure.billing;

import io.github.thgrcarvalho.zelo.application.BillingProvider;
import io.github.thgrcarvalho.zelo.application.error.ConflictException;
import io.github.thgrcarvalho.zelo.application.error.ServiceUnavailableException;
import io.github.thgrcarvalho.zelo.domain.account.Account;
import io.github.thgrcarvalho.zelo.domain.account.AccountRepository;
import io.github.thgrcarvalho.zelo.domain.account.Plan;
import io.github.thgrcarvalho.zelo.domain.account.PlanStatus;
import io.github.thgrcarvalho.zelo.infrastructure.config.ZeloProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Asaas checkout, ported from Vitalio's prod-proven shape: create-or-reuse the
 * customer, GET-before-POST subscription reuse, then poll the subscription's
 * first (async-created) payment for its hosted {@code invoiceUrl}. The returned
 * URL must sit on the Asaas invoice origin derived from the configured API base
 * (sandbox vs www) — a proxy that rewrote the API response can't redirect the
 * browser elsewhere. Create POSTs are never retried (double-billing risk).
 *
 * <p>Deliberately NOT one big transaction: each provider-side id is committed
 * the moment Asaas mints it (short {@code save()} transactions), so an
 * "invoice not ready yet" failure — or a crash mid-flow — can never roll back
 * the ids and trick a retry into minting a duplicate customer or subscription.
 * No DB connection is held across the provider's HTTP calls or the poll sleeps.</p>
 *
 * <p>A striped in-JVM lock serializes double-clicked checkouts — fine for the
 * single-instance deployment; scale-out would need a shared lock.</p>
 */
@Component
public class AsaasBillingProvider implements BillingProvider {

    private static final Logger log = LoggerFactory.getLogger(AsaasBillingProvider.class);
    private static final int INVOICE_POLL_ATTEMPTS = 3;
    private static final long INVOICE_POLL_GAP_MS = 400;
    private static final int LOCK_STRIPES = 64;

    private final ZeloProperties.Billing config;
    private final AccountRepository accounts;
    private final AsaasClient client;
    private final String allowedInvoicePrefix;
    private final ReentrantLock[] checkoutLocks = new ReentrantLock[LOCK_STRIPES];

    public AsaasBillingProvider(ZeloProperties properties, AccountRepository accounts,
                                RestClient.Builder restClientBuilder) {
        this.config = properties.getBilling();
        this.accounts = accounts;
        this.client = config.isEnabled()
                ? new AsaasClient(restClientBuilder, config.getAsaasBaseUrl(), config.getAsaasApiKey())
                : null;
        this.allowedInvoicePrefix = config.getAsaasBaseUrl().contains("sandbox")
                ? "https://sandbox.asaas.com/"
                : "https://www.asaas.com/";
        for (int i = 0; i < LOCK_STRIPES; i++) {
            checkoutLocks[i] = new ReentrantLock();
        }
    }

    @Override
    public boolean isEnabled() {
        return config.isEnabled();
    }

    @Override
    public String createCheckout(Account account) {
        if (!isEnabled()) {
            throw new ServiceUnavailableException("Billing is not enabled on this deployment");
        }
        ReentrantLock lock = checkoutLocks[Math.floorMod(account.getId().hashCode(), LOCK_STRIPES)];
        lock.lock();
        try {
            // Re-read inside the lock: a concurrent click may have finished checkout.
            Account fresh = accounts.findById(account.getId()).orElseThrow();
            if (fresh.getPlan() == Plan.PRO
                    && (fresh.getPlanStatus() == PlanStatus.ACTIVE || fresh.getPlanStatus() == PlanStatus.OVERDUE)) {
                throw new ConflictException("This account is already on the PRO plan.");
            }

            String customerId = fresh.getBillingCustomerId();
            if (customerId == null) {
                customerId = client.createCustomer(fresh.getOrgName(), fresh.getEmail());
                persistBillingIds(fresh.getId(), customerId, fresh.getBillingSubscriptionId());
            }

            String externalReference = "plan=PRO;account=" + fresh.getId();
            String subscriptionId = findReusableSubscription(customerId, externalReference);
            if (subscriptionId == null) {
                subscriptionId = client.createSubscription(
                        customerId, config.getProPriceBrl(), externalReference, "Zelo PRO");
            }
            persistBillingIds(fresh.getId(), customerId, subscriptionId);

            String invoiceUrl = firstInvoiceUrl(subscriptionId);
            if (invoiceUrl == null) {
                // Both ids are already committed above, so a retry reuses them.
                throw new ServiceUnavailableException(
                        "Checkout was created but its payment link is not ready yet — try again in a moment.");
            }
            return invoiceUrl;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Commits the provider-side ids immediately — {@code save()} runs in its own
     * short transaction, so nothing later in the flow can roll them back.
     */
    private void persistBillingIds(UUID accountId, String customerId, String subscriptionId) {
        Account account = accounts.findById(accountId).orElseThrow();
        account.attachBilling(customerId, subscriptionId);
        accounts.save(account);
    }

    private String findReusableSubscription(String customerId, String externalReference) {
        for (AsaasClient.Subscription subscription : client.subscriptionsOf(customerId)) {
            if ("ACTIVE".equals(subscription.status())
                    && externalReference.equals(subscription.externalReference())) {
                return subscription.id();
            }
        }
        return null;
    }

    /**
     * The first charge is created asynchronously by Asaas — poll briefly. Prefer
     * a PENDING/OVERDUE payment's URL (a paid stale charge's invoice is useless),
     * fall back to any non-blank one; accept only the expected Asaas origin.
     */
    private String firstInvoiceUrl(String subscriptionId) {
        for (int attempt = 0; attempt < INVOICE_POLL_ATTEMPTS; attempt++) {
            if (attempt > 0) {
                try {
                    Thread.sleep(INVOICE_POLL_GAP_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            List<AsaasClient.Payment> payments = client.paymentsOf(subscriptionId);
            String fallback = null;
            for (AsaasClient.Payment payment : payments) {
                if (!validInvoiceUrl(payment.invoiceUrl())) {
                    continue;
                }
                if ("PENDING".equals(payment.status()) || "OVERDUE".equals(payment.status())) {
                    return payment.invoiceUrl();
                }
                if (fallback == null) {
                    fallback = payment.invoiceUrl();
                }
            }
            if (fallback != null) {
                return fallback;
            }
        }
        log.warn("Asaas: no invoice URL for subscription {} after {} polls",
                subscriptionId, INVOICE_POLL_ATTEMPTS);
        return null;
    }

    private boolean validInvoiceUrl(String url) {
        return url != null && url.startsWith(allowedInvoicePrefix);
    }
}
