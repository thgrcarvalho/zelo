package io.github.thgrcarvalho.zelo.application;

import io.github.thgrcarvalho.zelo.domain.consent.ConsentAction;

import java.time.Instant;

/** One consent event, enriched with the human-readable purpose key, for export. */
public record ConsentHistoryItem(
        String purposeKey,
        ConsentAction action,
        String source,
        Instant occurredAt
) {
}
