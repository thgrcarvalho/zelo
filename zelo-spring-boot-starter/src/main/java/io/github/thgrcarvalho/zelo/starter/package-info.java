/**
 * Zelo Spring Boot starter. Embed in an integrator application to receive and
 * verify signed Zelo webhooks and auto-fulfill data-subject requests.
 *
 * <p>Annotate a method with {@link io.github.thgrcarvalho.zelo.starter.ZeloWebhook}
 * to handle an event; the starter verifies the HMAC signature, dispatches the
 * event, and fulfills the request with the method's return value as proof.
 * Configure via {@code zelo.api-url}, {@code zelo.api-key} and
 * {@code zelo.webhook-secret}.</p>
 */
package io.github.thgrcarvalho.zelo.starter;
