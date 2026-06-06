-- Zelo core schema (foundation): integrators, subjects, purposes.
--
-- Hard invariant: NO PII anywhere. external_id is the only link back to a real
-- person, and it lives in the integrator's own database. Zelo stores only the
-- opaque value.

-- Legal bases under LGPD Art. 7 — the ten hypotheses that authorise processing
-- of personal data.
CREATE TYPE legal_basis AS ENUM (
    'CONSENT',                      -- Art. 7, I
    'LEGAL_OBLIGATION',             -- Art. 7, II
    'PUBLIC_POLICY',                -- Art. 7, III
    'RESEARCH',                     -- Art. 7, IV
    'CONTRACT',                     -- Art. 7, V
    'REGULAR_EXERCISE_OF_RIGHTS',   -- Art. 7, VI
    'VITAL_INTERESTS',              -- Art. 7, VII (protection of life/safety)
    'HEALTH_PROTECTION',            -- Art. 7, VIII
    'LEGITIMATE_INTEREST',          -- Art. 7, IX
    'CREDIT_PROTECTION'             -- Art. 7, X
);

-- One row per integrator application. The API key scopes every other row,
-- which keeps this forward-compatible with multi-tenancy.
CREATE TABLE api_keys (
    id             UUID         PRIMARY KEY,
    key_hash       VARCHAR(64)  NOT NULL UNIQUE,   -- SHA-256 hex of the raw API key
    name           VARCHAR(255) NOT NULL,
    webhook_url    VARCHAR(2048),
    webhook_secret VARCHAR(255),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- A data subject, referenced only by the integrator's own opaque id. No PII.
CREATE TABLE subjects (
    id          UUID         PRIMARY KEY,
    api_key_id  UUID         NOT NULL REFERENCES api_keys (id),
    external_id VARCHAR(255) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (api_key_id, external_id)
);

-- A declared processing purpose, with its LGPD legal basis.
CREATE TABLE purposes (
    id          UUID          PRIMARY KEY,
    api_key_id  UUID          NOT NULL REFERENCES api_keys (id),
    key         VARCHAR(255)  NOT NULL,
    description VARCHAR(1024) NOT NULL,
    legal_basis legal_basis   NOT NULL,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    UNIQUE (api_key_id, key)
);

CREATE INDEX idx_subjects_api_key ON subjects (api_key_id);
CREATE INDEX idx_purposes_api_key ON purposes (api_key_id);
