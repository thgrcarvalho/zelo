-- Runtime-provisioned keys need two things the bootstrap-only model never did.
-- 1) REVOCATION without deletion: a hard DELETE of an api_keys row would cascade
--    away the tenant's subjects and its tamper-evident audit chain, destroying
--    the compliance record. revoked_at soft-disables the key (the auth filter
--    rejects it) while every historical row stays intact.
-- 2) A billing TIER tag so a future billing layer can tell a comped/internal
--    account (e.g. the Vitalio dogfood) from a paying one — without wiring any
--    billing now.
-- Both are nullable: existing rows are active (revoked_at IS NULL) and untiered.
ALTER TABLE api_keys ADD COLUMN revoked_at TIMESTAMPTZ;
ALTER TABLE api_keys ADD COLUMN tier       VARCHAR(32);
