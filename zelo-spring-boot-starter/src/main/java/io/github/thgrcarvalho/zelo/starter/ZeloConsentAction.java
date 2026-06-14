package io.github.thgrcarvalho.zelo.starter;

/**
 * A consent decision recorded on the (append-only) ledger. The names match the
 * server's enum and travel on the wire by name.
 */
public enum ZeloConsentAction {

    /** The subject grants consent for a purpose. */
    GRANT,
    /** The subject withdraws a previously granted consent (LGPD Art. 8 §5). */
    WITHDRAW
}
