-- Per-account brute-force lockout for /account/login. A per-IP throttle (nginx +
-- the in-app @RateLimit) does nothing against a botnet hammering one victim account,
-- so the account itself counts consecutive failures and locks for a cooldown window.
-- failed_login_count resets on a successful login or a password reset; locked_until,
-- once in the future, rejects logins (429) regardless of the password supplied.
ALTER TABLE accounts ADD COLUMN failed_login_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE accounts ADD COLUMN locked_until TIMESTAMPTZ;
