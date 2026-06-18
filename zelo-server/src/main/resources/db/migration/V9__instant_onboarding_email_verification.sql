-- Instant self-serve onboarding: email verification REPLACES operator approval.
--
-- V8 introduced an approval queue (role/approved_by/approved_at, PENDING status).
-- The product is now instant self-serve: signup → UNVERIFIED → click the emailed
-- verification link → ACTIVE (mints keys). There is no operator and no approval.
-- V8 was never deployed, so this forward-only migration simply drops the approval
-- machinery and adds verification + password-reset support; on the box it runs
-- right after V8 on the first rebuild.

-- 1. Drop the operator/approval model.
ALTER TABLE accounts DROP CONSTRAINT IF EXISTS fk_accounts_approved_by;
ALTER TABLE accounts DROP COLUMN IF EXISTS approved_by;
ALTER TABLE accounts DROP COLUMN IF EXISTS approved_at;
ALTER TABLE accounts DROP COLUMN IF EXISTS role;

-- 2. Verification + session-invalidation columns.
--    email_verified_at: null = unverified (the gate). password_changed_at: a
--    monotonic watermark baked into session tokens so a password reset invalidates
--    every live cookie.
ALTER TABLE accounts ADD COLUMN email_verified_at   TIMESTAMPTZ;
ALTER TABLE accounts ADD COLUMN password_changed_at TIMESTAMPTZ NOT NULL DEFAULT now();

-- 3. Re-home the lifecycle: signup now lands UNVERIFIED (was PENDING). Grandfather
--    any pre-existing rows (the deployed box has none yet) as verified + active so
--    a deploy never bounces a live account back to re-verify.
ALTER TABLE accounts ALTER COLUMN status SET DEFAULT 'UNVERIFIED';
UPDATE accounts SET email_verified_at = COALESCE(email_verified_at, created_at);
UPDATE accounts SET status = 'ACTIVE' WHERE status IN ('PENDING', 'REJECTED');

-- 4. Single-use, hashed-at-rest, purpose-bound tokens for email verification and
--    password reset. The raw token lives only in the email link; we store its
--    SHA-256 hex (same discipline as api_keys.key_hash) and mark single-use via
--    used_at (an atomic UPDATE ... WHERE used_at IS NULL on redeem).
CREATE TABLE account_tokens (
    id          UUID         PRIMARY KEY,
    account_id  UUID         NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
    purpose     VARCHAR(32)  NOT NULL,            -- EMAIL_VERIFICATION | PASSWORD_RESET (enum name)
    token_hash  VARCHAR(64)  NOT NULL UNIQUE,     -- SHA-256 hex of the raw token
    expires_at  TIMESTAMPTZ  NOT NULL,
    used_at     TIMESTAMPTZ,                      -- null = unredeemed (single-use marker)
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Lookups for the per-account cooldown / daily-cap (account_id + purpose). The
-- redeem path looks up by token_hash, already covered by its UNIQUE index.
CREATE INDEX idx_account_tokens_account_purpose ON account_tokens (account_id, purpose);

-- The stale-row purge deletes WHERE expires_at <= now AND created_at < cutoff;
-- expires_at leads so the planner range-seeks the (small) set of expired rows
-- instead of scanning the whole append-only table as it grows.
CREATE INDEX idx_account_tokens_expires_created ON account_tokens (expires_at, created_at);
