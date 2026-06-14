package io.github.thgrcarvalho.zelo.starter;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Instant;
import java.util.Map;

/**
 * The live state of a data-subject request, as returned by
 * {@code GET /v1/requests/{id}}. Use it to show a deletion's progress or to
 * confirm fulfillment.
 *
 * @param type                  the request type (only {@code DELETE} in v1)
 * @param secondsUntilDeadline  time left until the SLA deadline (negative once passed)
 * @param fulfillmentProof      the integrator's proof, present once {@code FULFILLED}
 */
public record ZeloRequest(String id, String type, ZeloRequestStatus status,
                          Instant deadlineAt, long secondsUntilDeadline,
                          Instant createdAt, Instant dispatchedAt, Instant fulfilledAt,
                          Map<String, Object> fulfillmentProof) {

    /**
     * Whether the request has reached its terminal, fulfilled state. {@code @JsonIgnore}
     * so it is not mistaken for a {@code "fulfilled"} wire field if an integrator
     * serialises this record from their own controller.
     */
    @JsonIgnore
    public boolean isFulfilled() {
        return status == ZeloRequestStatus.FULFILLED;
    }
}
