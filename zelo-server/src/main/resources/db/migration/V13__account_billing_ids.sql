-- Provider-side identifiers for the billing integration (Asaas first). Nullable:
-- set on first checkout; customer id is reused on later checkouts so one account
-- never mints duplicate provider customers.
ALTER TABLE accounts ADD COLUMN billing_customer_id     VARCHAR(64);
ALTER TABLE accounts ADD COLUMN billing_subscription_id VARCHAR(64);
