-- Plan layer for the billing MVP. plan gates the free-tier ceilings (PRO and
-- account-less keys are unlimited); plan_status is the billing lifecycle slot
-- the payment provider webhook will drive later (NONE until billing exists).
ALTER TABLE accounts ADD COLUMN plan        VARCHAR(16) NOT NULL DEFAULT 'FREE';
ALTER TABLE accounts ADD COLUMN plan_status VARCHAR(16) NOT NULL DEFAULT 'NONE';

-- One row per usage-threshold email actually sent, so the hourly alert job is
-- idempotent per (account, month, metric, threshold). Send-then-record: a failed
-- send leaves no row and is retried next hour.
CREATE TABLE usage_alerts (
    account_id    UUID        NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
    month         DATE        NOT NULL,
    metric        VARCHAR(32) NOT NULL,
    threshold_pct SMALLINT    NOT NULL,
    sent_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (account_id, month, metric, threshold_pct)
);
