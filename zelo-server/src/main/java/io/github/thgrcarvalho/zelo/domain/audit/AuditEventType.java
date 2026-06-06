package io.github.thgrcarvalho.zelo.domain.audit;

/**
 * The event types written to the audit log. Stable strings — they are part of
 * the hashed material, so renaming one would not match historical entries.
 */
public final class AuditEventType {

    public static final String SUBJECT_REGISTERED = "subject.registered";
    public static final String PURPOSE_CREATED = "purpose.created";
    public static final String CONSENT_GRANTED = "consent.granted";
    public static final String CONSENT_WITHDRAWN = "consent.withdrawn";

    // DSR lifecycle (M2/M3/M6).
    public static final String DSR_DELETE_REQUESTED = "dsr.delete.requested";
    public static final String DSR_DELETE_DISPATCHED = "dsr.delete.dispatched";
    public static final String DSR_DELETE_FULFILLED = "dsr.delete.fulfilled";
    public static final String DSR_DELETE_OVERDUE = "dsr.delete.overdue";

    private AuditEventType() {
    }
}
