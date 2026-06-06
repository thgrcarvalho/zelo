-- Data-subject requests. v1 implements the DELETE loop only.

CREATE TYPE dsr_type AS ENUM ('DELETE');
CREATE TYPE dsr_status AS ENUM ('RECEIVED', 'DISPATCHED', 'FULFILLED', 'OVERDUE');

-- A mutable aggregate (the status advances through its lifecycle) → JPA.
-- api_key_id is denormalized from the subject so requests can be scoped and the
-- overdue sweep grouped without a join.
CREATE TABLE dsr_requests (
    id                UUID        PRIMARY KEY,
    api_key_id        UUID        NOT NULL REFERENCES api_keys (id),
    subject_id        UUID        NOT NULL REFERENCES subjects (id),
    type              dsr_type    NOT NULL,
    status            dsr_status  NOT NULL,
    deadline_at       TIMESTAMPTZ NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    dispatched_at     TIMESTAMPTZ,
    fulfilled_at      TIMESTAMPTZ,
    fulfillment_proof JSONB
);

CREATE INDEX idx_dsr_requests_api_key ON dsr_requests (api_key_id, created_at DESC);
-- Drives the overdue sweep: find still-open requests past their deadline.
CREATE INDEX idx_dsr_requests_status_deadline ON dsr_requests (status, deadline_at);
