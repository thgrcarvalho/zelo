-- The consent ledger and the audit log. Both are append-only.

CREATE TYPE consent_action AS ENUM ('GRANT', 'WITHDRAW');

-- Append-only consent ledger. Current state for a (subject, purpose) is the
-- latest event. Withdrawing consent writes a new WITHDRAW event; it never
-- mutates or deletes a prior one.
CREATE TABLE consent_events (
    id          BIGINT         GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    subject_id  UUID           NOT NULL REFERENCES subjects (id),
    purpose_id  UUID           NOT NULL REFERENCES purposes (id),
    action      consent_action NOT NULL,
    source      VARCHAR(255),
    occurred_at TIMESTAMPTZ    NOT NULL,
    metadata    JSONB          NOT NULL DEFAULT '{}',
    created_at  TIMESTAMPTZ    NOT NULL DEFAULT now()
);

-- Latest-per-purpose lookups and full history both read this index.
CREATE INDEX idx_consent_events_subject_purpose
    ON consent_events (subject_id, purpose_id, occurred_at DESC, id DESC);

-- Append-only, hash-chained audit log — the tamper-evident proof trail.
-- One independent chain per integrator (api_key). The chain order within a key
-- is the ascending id. The hash rule (see HashChain.java):
--
--   entry_hash = SHA-256( prev_hash \n event_type \n canonical_json(payload) \n occurred_at )
--
-- with the genesis prev_hash being 64 zero characters.
CREATE TABLE audit_log (
    id          BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    api_key_id  UUID         NOT NULL REFERENCES api_keys (id),
    event_type  VARCHAR(255) NOT NULL,
    payload     JSONB        NOT NULL,
    occurred_at TIMESTAMPTZ  NOT NULL,
    prev_hash   CHAR(64)     NOT NULL,
    entry_hash  CHAR(64)     NOT NULL,
    -- No two entries in the same chain may share a predecessor. This makes a
    -- silent fork — or a replayed genesis entry — impossible at the storage
    -- layer, independent of the application-level serialization.
    CONSTRAINT uq_audit_chain_link UNIQUE (api_key_id, prev_hash)
);

CREATE INDEX idx_audit_log_api_key_id ON audit_log (api_key_id, id);
