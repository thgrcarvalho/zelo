-- Enforce at most one OPEN (RECEIVED/DISPATCHED) deletion request per subject, so a
-- double-submit cannot fan out into duplicate webhooks + audit entries. The application
-- returns the existing open request; this partial unique index is the concurrency
-- backstop — a racing second insert fails (→ 409) instead of creating a duplicate.
CREATE UNIQUE INDEX uq_open_delete_per_subject
    ON dsr_requests (subject_id)
    WHERE status IN ('RECEIVED', 'DISPATCHED');
