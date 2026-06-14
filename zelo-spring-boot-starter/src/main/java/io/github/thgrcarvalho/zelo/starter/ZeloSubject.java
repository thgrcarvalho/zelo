package io.github.thgrcarvalho.zelo.starter;

import java.time.Instant;

/**
 * A data subject as Zelo knows it: an opaque {@code externalId} (your own user
 * id) and the moment Zelo first saw it. Zelo never holds the subject's PII.
 *
 * @param id         Zelo's internal id for the subject
 * @param externalId your application's user identifier
 * @param createdAt  when the subject was first registered at Zelo
 */
public record ZeloSubject(String id, String externalId, Instant createdAt) {
}
