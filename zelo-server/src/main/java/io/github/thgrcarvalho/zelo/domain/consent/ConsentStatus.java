package io.github.thgrcarvalho.zelo.domain.consent;

import java.time.Instant;

/**
 * The current consent state for one purpose: the latest action wins.
 *
 * @param purposeKey the purpose's key
 * @param granted    true if the latest action was GRANT
 * @param lastAction the latest action
 * @param source     the source of the latest action
 * @param since      when the latest action occurred
 */
public record ConsentStatus(
        String purposeKey,
        boolean granted,
        ConsentAction lastAction,
        String source,
        Instant since
) {
}
