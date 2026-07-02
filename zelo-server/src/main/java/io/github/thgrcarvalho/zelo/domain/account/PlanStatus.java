package io.github.thgrcarvalho.zelo.domain.account;

/**
 * Billing lifecycle of the plan, driven by the payment provider's webhook once
 * billing exists. NONE = no billing relationship (every account until then).
 */
public enum PlanStatus {
    NONE, ACTIVE, OVERDUE, CANCELED
}
