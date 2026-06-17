-- Self-service onboarding: integrator accounts.
--
-- An account is the human-facing owner of one or more API keys. It is the
-- self-service replacement for operator-minted-only keys: an integrator signs
-- up, an operator approves, then the account mints its own keys via /account/**.
--
-- This table holds the integrator's B2B contact data (email + org name). That is
-- a DIFFERENT category from end-user PII: Zelo's "zero PII" invariant is about the
-- data subjects an integrator manages, never about who the integrator is. No
-- end-user PII lands here.
CREATE TABLE accounts (
    id            UUID         PRIMARY KEY,
    email         VARCHAR(320) NOT NULL UNIQUE,      -- stored lowercased
    password_hash VARCHAR(255) NOT NULL,             -- PBKDF2, self-describing format
    org_name      VARCHAR(200) NOT NULL,
    role          VARCHAR(16)  NOT NULL DEFAULT 'USER',    -- USER | OPERATOR
    status        VARCHAR(16)  NOT NULL DEFAULT 'PENDING', -- PENDING | ACTIVE | REJECTED
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    approved_at   TIMESTAMPTZ,                        -- when an operator decided (approve/reject)
    approved_by   UUID                                -- operator account id that decided, nullable
);

-- An API key now optionally belongs to an account. Nullable: bootstrap/operator-
-- minted keys predate accounts and stay account-less (still valid). Self-issued
-- keys carry their owning account so the /account API can scope every read/write
-- by account_id and enforce tenant isolation in the service.
ALTER TABLE api_keys ADD COLUMN account_id UUID;
CREATE INDEX idx_api_keys_account ON api_keys (account_id);
ALTER TABLE api_keys ADD CONSTRAINT fk_api_keys_account
    FOREIGN KEY (account_id) REFERENCES accounts (id);
