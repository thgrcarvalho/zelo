package io.github.thgrcarvalho.zelo.application;

import io.github.thgrcarvalho.zelo.domain.dsr.DsrRequest;
import io.github.thgrcarvalho.zelo.domain.dsr.DsrRequestRepository;
import io.github.thgrcarvalho.zelo.domain.dsr.DsrStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Flags still-open requests whose deadline has passed. A request going OVERDUE is
 * itself an audited compliance event — the audit trail shows that Zelo noticed
 * the SLA was missed.
 *
 * <p>The sweep itself is <em>not</em> transactional: each request is flagged in
 * its own transaction (via {@link DsrService#markOverdueIfOpen}), so one tenant's
 * conflict or failure never rolls back the others and each per-key audit advisory
 * lock is held only briefly. The work list is paged to bound a backlog.</p>
 */
@Service
public class OverdueSweepService {

    private static final Logger log = LoggerFactory.getLogger(OverdueSweepService.class);
    private static final List<DsrStatus> OPEN = List.of(DsrStatus.RECEIVED, DsrStatus.DISPATCHED);
    private static final int MAX_PER_RUN = 500;

    private final DsrRequestRepository requests;
    private final DsrService dsrService;

    public OverdueSweepService(DsrRequestRepository requests, DsrService dsrService) {
        this.requests = requests;
        this.dsrService = dsrService;
    }

    /** Mark up to {@value #MAX_PER_RUN} open, past-deadline requests OVERDUE. Returns how many were flagged. */
    public int sweep() {
        Instant now = Instant.now();
        List<DsrRequest> due = requests.findByStatusInAndDeadlineAtBefore(
                OPEN, now, PageRequest.of(0, MAX_PER_RUN));

        int flagged = 0;
        for (DsrRequest request : due) {
            try {
                if (dsrService.markOverdueIfOpen(request.getApiKeyId(), request.getId())) {
                    flagged++;
                }
            } catch (ObjectOptimisticLockingFailureException e) {
                // The request advanced concurrently (e.g. was just fulfilled) — skip it.
                log.debug("Skipped request {} during sweep: concurrently modified", request.getId());
            }
        }
        if (flagged > 0) {
            log.warn("Overdue sweep flagged {} request(s) past their deadline", flagged);
        }
        return flagged;
    }
}
