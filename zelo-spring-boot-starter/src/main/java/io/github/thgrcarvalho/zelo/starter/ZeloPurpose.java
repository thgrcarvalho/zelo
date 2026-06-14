package io.github.thgrcarvalho.zelo.starter;

import java.time.Instant;

/**
 * A declared processing purpose with its LGPD legal basis. Consent is always
 * recorded <em>against</em> a purpose, so an app declares its purposes once
 * (e.g. at startup) before recording consent.
 *
 * @param id          Zelo's internal id for the purpose
 * @param key         the stable key you reference when recording consent
 * @param description a human-readable description
 * @param legalBasis  the LGPD Art. 7 basis this purpose relies on
 * @param createdAt   when the purpose was declared
 */
public record ZeloPurpose(String id, String key, String description,
                          ZeloLegalBasis legalBasis, Instant createdAt) {
}
