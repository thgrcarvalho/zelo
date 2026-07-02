-- Monthly usage rollups per account, one row per (account, calendar month, UTC).
-- Every source table is append-only or stamped at creation, so a finished month
-- is immutable: the nightly job upserts it idempotently. The CURRENT month is
-- never stored — it is always computed live from the source tables.
CREATE TABLE usage_rollups (
    account_id     UUID        NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
    month          DATE        NOT NULL,  -- first day of the month
    subjects       BIGINT      NOT NULL DEFAULT 0,
    consent_events BIGINT      NOT NULL DEFAULT 0,
    audit_events   BIGINT      NOT NULL DEFAULT 0,
    dsr_requests   BIGINT      NOT NULL DEFAULT 0,
    computed_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (account_id, month)
);
