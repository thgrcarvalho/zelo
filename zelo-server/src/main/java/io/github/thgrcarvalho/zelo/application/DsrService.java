package io.github.thgrcarvalho.zelo.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.thgrcarvalho.zelo.application.error.ResourceNotFoundException;
import io.github.thgrcarvalho.zelo.domain.audit.AuditEventType;
import io.github.thgrcarvalho.zelo.domain.audit.AuditTrail;
import io.github.thgrcarvalho.zelo.domain.audit.HashChain;
import io.github.thgrcarvalho.zelo.domain.dsr.DsrRequest;
import io.github.thgrcarvalho.zelo.domain.dsr.DsrRequestRepository;
import io.github.thgrcarvalho.zelo.domain.subject.Subject;
import io.github.thgrcarvalho.zelo.infrastructure.config.ZeloProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * The DSR engine. v1 handles the DELETE lifecycle: create (RECEIVED, with a
 * computed deadline) and fulfill (→ FULFILLED). Each transition writes an audit
 * entry. The signed-webhook dispatch on creation is added in M3; the overdue
 * sweep in M6.
 */
@Service
public class DsrService {

    private final DsrRequestRepository requests;
    private final SubjectService subjectService;
    private final AuditTrail auditTrail;
    private final ZeloProperties properties;
    private final ObjectMapper json;

    public DsrService(DsrRequestRepository requests, SubjectService subjectService, AuditTrail auditTrail,
                      ZeloProperties properties, ObjectMapper json) {
        this.requests = requests;
        this.subjectService = subjectService;
        this.auditTrail = auditTrail;
        this.properties = properties;
        this.json = json;
    }

    @Transactional
    public DsrRequest createDeletionRequest(UUID apiKeyId, String externalId) {
        Subject subject = subjectService.upsert(apiKeyId, externalId);
        Instant now = Instant.now();
        Instant deadline = now.plus(Duration.ofDays(properties.getDsr().getDeleteDeadlineDays()));

        DsrRequest request = DsrRequest.received(UUID.randomUUID(), apiKeyId, subject.getId(), deadline, now);
        requests.save(request);

        auditTrail.append(apiKeyId, AuditEventType.DSR_DELETE_REQUESTED,
                json.createObjectNode()
                        .put("requestId", request.getId().toString())
                        .put("externalId", externalId)
                        .put("deadline", HashChain.formatOccurredAt(deadline)),
                now);
        return request;
    }

    @Transactional(readOnly = true)
    public DsrRequest get(UUID apiKeyId, UUID requestId) {
        return findOwned(apiKeyId, requestId);
    }

    @Transactional
    public DsrRequest fulfill(UUID apiKeyId, UUID requestId, Map<String, Object> proof) {
        DsrRequest request = findOwned(apiKeyId, requestId);
        Instant now = Instant.now();
        request.markFulfilled(now, proof);

        ObjectNode payload = json.createObjectNode().put("requestId", request.getId().toString());
        payload.set("proof", proof == null ? json.createObjectNode() : json.valueToTree(proof));
        auditTrail.append(apiKeyId, AuditEventType.DSR_DELETE_FULFILLED, payload, now);
        return request;
    }

    private DsrRequest findOwned(UUID apiKeyId, UUID requestId) {
        return requests.findByIdAndApiKeyId(requestId, apiKeyId)
                .orElseThrow(() -> new ResourceNotFoundException("No request with id '" + requestId + "'"));
    }
}
