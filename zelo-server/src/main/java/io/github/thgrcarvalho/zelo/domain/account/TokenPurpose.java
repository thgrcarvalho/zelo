package io.github.thgrcarvalho.zelo.domain.account;

/**
 * What an {@link AccountToken} authorizes. The purpose is stored on the row and
 * checked on redeem, so a verification token can never be used to reset a password
 * (and vice-versa). Stored as the enum name in a {@code VARCHAR} column.
 */
public enum TokenPurpose {
    EMAIL_VERIFICATION,
    PASSWORD_RESET
}
