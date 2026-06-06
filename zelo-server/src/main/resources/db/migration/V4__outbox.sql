-- Transactional outbox table for spring-boot-starter-outbox. Webhook events are
-- written here in the same transaction as the DSR they belong to, then delivered
-- by the starter's poller (SELECT ... FOR UPDATE SKIP LOCKED). Schema must match
-- what the starter's JdbcOutboxStore expects.
CREATE TABLE outbox_event (
    id           BIGSERIAL    PRIMARY KEY,
    event_type   VARCHAR(255) NOT NULL,
    payload      TEXT         NOT NULL,
    headers      TEXT         NOT NULL DEFAULT '{}',
    status       VARCHAR(32)  NOT NULL DEFAULT 'PENDING',  -- PENDING | PUBLISHED | FAILED
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ,
    attempts     INTEGER      NOT NULL DEFAULT 0,
    last_error   TEXT
);

CREATE INDEX idx_outbox_status_id ON outbox_event (status, id)
    WHERE status = 'PENDING';
