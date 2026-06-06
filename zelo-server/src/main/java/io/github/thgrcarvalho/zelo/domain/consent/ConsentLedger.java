package io.github.thgrcarvalho.zelo.domain.consent;

import java.util.List;
import java.util.UUID;

/**
 * Port for the append-only consent ledger. Implemented with explicit SQL so the
 * append-only discipline is enforced directly (no accidental ORM mutation).
 */
public interface ConsentLedger {

    /** Append a consent event and return it with its assigned id. */
    ConsentEvent append(ConsentEvent event);

    /**
     * Full history for a subject, oldest first. If {@code purposeId} is non-null,
     * restrict to that purpose.
     */
    List<ConsentEvent> history(UUID subjectId, UUID purposeId);

    /** Current consent state per purpose for a subject (latest event wins). */
    List<ConsentStatus> currentState(UUID subjectId);
}
