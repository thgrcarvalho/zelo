package io.github.thgrcarvalho.zelo.starter;

/**
 * The payload of a {@code dsr.delete.requested} webhook, as delivered to a
 * {@link ZeloWebhook} handler.
 *
 * @param requestId  the Zelo request id (used to auto-fulfill)
 * @param externalId the integrator's own user id to erase
 * @param deadline   ISO-8601 instant by which the request must be fulfilled
 */
public record ZeloDeletionRequest(String requestId, String externalId, String deadline) {
}
