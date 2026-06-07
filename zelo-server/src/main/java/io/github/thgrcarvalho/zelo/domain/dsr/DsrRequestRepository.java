package io.github.thgrcarvalho.zelo.domain.dsr;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DsrRequestRepository extends JpaRepository<DsrRequest, UUID> {

    /** Scoped lookup: a request is only visible to the integrator that owns it. */
    Optional<DsrRequest> findByIdAndApiKeyId(UUID id, UUID apiKeyId);

    /**
     * Open requests whose deadline has passed — the overdue sweep's work list (M6).
     * Bounded by {@code pageable} so a backlog is drained in committed chunks rather
     * than one unbounded transaction.
     */
    List<DsrRequest> findByStatusInAndDeadlineAtBefore(List<DsrStatus> statuses, Instant cutoff, Pageable pageable);
}
