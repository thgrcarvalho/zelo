package io.github.thgrcarvalho.zelo.starter;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as the handler for a Zelo webhook event. The starter verifies
 * the signature, dispatches the (already-validated) event to this method, and —
 * on normal return — auto-fulfills the request back to Zelo using the method's
 * return value as the fulfillment proof.
 *
 * <pre>{@code
 * @ZeloWebhook("dsr.delete.requested")
 * public Map<String, Object> erase(ZeloDeletionRequest request) {
 *     int rows = users.deleteByExternalId(request.externalId());
 *     return Map.of("deletedRows", rows);   // becomes the fulfillment proof
 * }
 * }</pre>
 *
 * <p>The method may take a single {@link ZeloDeletionRequest} parameter (or none)
 * and may return a {@code Map}/POJO (used as proof) or {@code void}. Throwing
 * signals failure: the starter returns a non-2xx response and Zelo retries.</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ZeloWebhook {

    /** The event type this method handles, e.g. {@code "dsr.delete.requested"}. */
    String value() default "dsr.delete.requested";
}
