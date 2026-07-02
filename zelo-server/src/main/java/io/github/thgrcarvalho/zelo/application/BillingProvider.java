package io.github.thgrcarvalho.zelo.application;

import io.github.thgrcarvalho.zelo.domain.account.Account;

/**
 * Port to the payment provider's checkout. One implementation today (Asaas);
 * the webhook side stays provider-specific in the web layer, which translates
 * provider events into the neutral transitions on {@code BillingEventService}.
 */
public interface BillingProvider {

    /** False while the provider is not configured — checkout must 503, not 500. */
    boolean isEnabled();

    /**
     * Creates (or reuses) the provider-side customer + subscription for the
     * account and returns the hosted checkout URL the browser should open.
     */
    String createCheckout(Account account);
}
