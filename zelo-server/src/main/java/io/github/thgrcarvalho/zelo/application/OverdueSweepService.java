package io.github.thgrcarvalho.zelo.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.thgrcarvalho.zelo.domain.audit.AuditEventType;
import io.github.thgrcarvalho.zelo.domain.audit.AuditTrail;
import io.github.thgrcarvalho.zelo.domain.audit.HashChain;
import io.github.thgrcarvalho.zelo.domain.dsr.DsrRequest;
import io.github.thgrcarvalho.zelo.domain.dsr.DsrRequestRepository;
import io.github.thgrcarvalho.zelo.domain.dsr.DsrStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Flags still-open requests whose deadline has passed. A request going OVERDUE is
 * itself an audited compliance event — the audit trail shows that Zelo noticed
 * the SLA was missed.
 */
@Service
public class OverdueSweepService {

    private static final Logger log = LoggerFactory.getLogger(OverdueSweepService.class);
    private static final List<DsrStatus> OPEN = List.of(DsrStatus.RECEIVED, DsrStatus.DISPATCHED);

    private final DsrRequestRepository requests;
    private final AuditTrail auditTrail;
    private final ObjectMapper json;

    public OverdueSweepService(DsrRequestRepository requests, AuditTrail auditTrail, ObjectMapper json) {
        this.requests = requests;
        this.auditTrail = auditTrail;
        this.json = json;
    }

    /** Mark every open, past-deadline request OVERDUE. Returns how many were flagged. */
    @Transactional
    public int sweep() {
        Instant now = Instant.now();
        List<DsrRequest> overdue = requests.findByStatusInAndDeadlineAtBefore(OPEN, now);
        for (DsrRequest request : overdue) {
            request.markOverdue();
            auditTrail.append(request.getApiKeyId(), AuditEventType.DSR_DELETE_OVERDUE,
                    json.createObjectNode()
                            .put("requestId", request.getId().toString())
                            .put("deadline", HashChain.formatOccurredAt(request.getDeadlineAt())),
                    now);
        }
        if (!overdue.isEmpty()) {
            log.warn("Overdue sweep flagged {} request(s) past their deadline", overdue.size());
        }
        return overdue.size();
    }
}
