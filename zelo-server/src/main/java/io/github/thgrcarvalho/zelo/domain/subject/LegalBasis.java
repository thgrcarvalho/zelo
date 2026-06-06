package io.github.thgrcarvalho.zelo.domain.subject;

/**
 * The ten legal bases for processing personal data under LGPD (Lei 13.709/2018)
 * Art. 7. Maps to the {@code legal_basis} Postgres enum; names must match exactly.
 */
public enum LegalBasis {
    CONSENT,                    // Art. 7, I
    LEGAL_OBLIGATION,           // Art. 7, II
    PUBLIC_POLICY,              // Art. 7, III
    RESEARCH,                   // Art. 7, IV
    CONTRACT,                   // Art. 7, V
    REGULAR_EXERCISE_OF_RIGHTS, // Art. 7, VI
    VITAL_INTERESTS,            // Art. 7, VII
    HEALTH_PROTECTION,          // Art. 7, VIII
    LEGITIMATE_INTEREST,        // Art. 7, IX
    CREDIT_PROTECTION           // Art. 7, X
}
