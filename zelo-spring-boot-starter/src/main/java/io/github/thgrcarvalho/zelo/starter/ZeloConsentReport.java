package io.github.thgrcarvalho.zelo.starter;

import java.time.Instant;
import java.util.List;

/**
 * The consent picture for one subject: the {@code current} state per purpose
 * (the latest decision) plus the full append-only {@code history}.
 *
 * @param externalId your application's user identifier
 * @param current    the latest decision for each purpose (optionally filtered to one)
 * @param history    every consent event, newest-relevant order as returned by Zelo
 */
public record ZeloConsentReport(String externalId, List<State> current, List<HistoryItem> history) {

    /** The latest decision for a single purpose. */
    public record State(String purposeKey, boolean granted, ZeloConsentAction lastAction,
                        String source, Instant since) {
    }

    /** One historical consent event. */
    public record HistoryItem(String purposeKey, ZeloConsentAction action, String source, Instant occurredAt) {
    }

    /**
     * Whether consent for {@code purposeKey} is currently granted. Convenient for
     * gating a feature: {@code if (report.isGranted("marketing-emails")) ...}.
     */
    public boolean isGranted(String purposeKey) {
        if (current == null) {
            return false;
        }
        return current.stream().anyMatch(s -> s.purposeKey().equals(purposeKey) && s.granted());
    }
}
