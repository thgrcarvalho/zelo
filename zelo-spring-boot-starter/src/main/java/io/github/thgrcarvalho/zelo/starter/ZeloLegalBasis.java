package io.github.thgrcarvalho.zelo.starter;

/**
 * The legal basis for processing personal data, mirroring the ten hypotheses of
 * LGPD Art. 7. A {@code ZeloPurpose} declares which one it relies on; for
 * sensitive data (LGPD Art. 11 — health, biometrics, etc.) the practical bases
 * are {@link #CONSENT} and {@link #HEALTH_PROTECTION}.
 *
 * <p>The constant names match the server's enum exactly — they are sent on the
 * wire by name — so do not rename them.</p>
 */
public enum ZeloLegalBasis {

    /** The data subject's explicit consent (Art. 7, I). */
    CONSENT,
    /** Compliance with a legal or regulatory obligation (Art. 7, II). */
    LEGAL_OBLIGATION,
    /** Execution of public policy by the public administration (Art. 7, III). */
    PUBLIC_POLICY,
    /** Studies by a research body, anonymising where possible (Art. 7, IV). */
    RESEARCH,
    /** Performance of a contract the subject is party to (Art. 7, V). */
    CONTRACT,
    /** Regular exercise of rights in proceedings (Art. 7, VI). */
    REGULAR_EXERCISE_OF_RIGHTS,
    /** Protection of the life or physical safety of a person (Art. 7, VII). */
    VITAL_INTERESTS,
    /** Health protection, in a procedure by health professionals/services (Art. 7, VIII / Art. 11). */
    HEALTH_PROTECTION,
    /** Legitimate interests of the controller or a third party (Art. 7, IX). */
    LEGITIMATE_INTEREST,
    /** Credit protection (Art. 7, X). */
    CREDIT_PROTECTION
}
