package io.github.thgrcarvalho.zelo.starter;

/**
 * The lifecycle state of a data-subject request, as reported by
 * {@code GET /v1/requests/{id}}. Names match the server's enum.
 */
public enum ZeloRequestStatus {

    /** Opened; the webhook has not yet been delivered. */
    RECEIVED,
    /** The signed webhook reached the integrator (2xx). */
    DISPATCHED,
    /** The integrator erased the data and reported proof. */
    FULFILLED,
    /** The deadline passed before fulfillment — an audited SLA miss. */
    OVERDUE
}
