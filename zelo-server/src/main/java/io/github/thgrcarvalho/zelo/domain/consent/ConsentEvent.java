package io.github.thgrcarvalho.zelo.domain.consent;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/**
 * An immutable entry in the append-only consent ledger.
 *
 * @param id         ledger position ({@code null} before insert)
 * @param subjectId  the subject
 * @param purposeId  the purpose
 * @param action     GRANT or WITHDRAW
 * @param source     free-text origin of the action (e.g. "checkout", "privacy-center")
 * @param occurredAt when the consent action happened
 * @param metadata   optional PII-free context
 */
public record ConsentEvent(
        Long id,
        UUID subjectId,
        UUID purposeId,
        ConsentAction action,
        String source,
        Instant occurredAt,
        JsonNode metadata
) {
}
