-- Self-service email change rides the existing single-use token machinery. The
-- pending new address lives on the token row (only EMAIL_CHANGE tokens set it),
-- so the swap happens exactly once, on redeem, to exactly the address the
-- confirmation link was delivered to.
ALTER TABLE account_tokens ADD COLUMN new_email VARCHAR(320);
