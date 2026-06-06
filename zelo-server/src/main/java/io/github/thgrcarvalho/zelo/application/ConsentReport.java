package io.github.thgrcarvalho.zelo.application;

import io.github.thgrcarvalho.zelo.domain.consent.ConsentStatus;

import java.util.List;

/** Current consent state plus full history for a subject. */
public record ConsentReport(
        String externalId,
        List<ConsentStatus> current,
        List<ConsentHistoryItem> history
) {
}
