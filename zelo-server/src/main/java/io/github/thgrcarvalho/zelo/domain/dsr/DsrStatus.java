package io.github.thgrcarvalho.zelo.domain.dsr;

/**
 * The DSR lifecycle. Maps to the {@code dsr_status} Postgres enum.
 *
 * <pre>
 *   RECEIVED ──dispatch──▶ DISPATCHED ──fulfill──▶ FULFILLED
 *      │                       │
 *      └──────past deadline────┴──▶ OVERDUE ──fulfill (late)──▶ FULFILLED
 * </pre>
 */
public enum DsrStatus {
    RECEIVED,
    DISPATCHED,
    FULFILLED,
    OVERDUE
}
