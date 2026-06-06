package io.github.thgrcarvalho.zelo.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.thgrcarvalho.outbox.OutboxEventPublisher;
import io.github.thgrcarvalho.zelo.application.error.ResourceNotFoundException;
import io.github.thgrcarvalho.zelo.domain.audit.AuditEventType;
import io.github.thgrcarvalho.zelo.domain.audit.AuditTrail;
import io.github.thgrcarvalho.zelo.domain.audit.HashChain;
import io.github.thgrcarvalho.zelo.domain.dsr.DsrRequest;
import io.github.thgrcarvalho.zelo.domain.dsr.DsrRequestRepository;
import io.github.thgrcarvalho.zelo.domain.dsr.DsrStatus;
import io.github.thgrcarvalho.zelo.domain.subject.Subject;
import io.github.thgrcarvalho.zelo.infrastructure.config.ZeloProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * The DSR engine. v1 handles the DELETE lifecycle: create (RECEIVED + a computed
 * deadline + a signed webhook queued to the transactional outbox) and fulfill
 * (→ FULFILLED). The webhook is delivered asynchronously by the outbox poller,
 * which flips the request to DISPATCHED on success (see markDispatchedIfPending).
 * Each transition writes an audit entry. The overdue sweep is M6.
 */
@Service
public class DsrService {

    /** Outbox-event header keys carrying the routing context to the delivery hook. */
    public static final String WEBHOOK_HEADER_API_KEY_ID = "zelo-api-key-id";
    public static final String WEBHOOK_HEADER_REQUEST_ID = "zelo-request-id";

    private final DsrRequestRepository requests;
    private final SubjectService subjectService;
    private final AuditTrail auditTrail;
    private final OutboxEventPublisher outbox;
    private final ZeloProperties properties;
    private final ObjectMapper json;

    public DsrService(DsrRequestRepository requests, SubjectService subjectService, AuditTrail auditTrail,
                      OutboxEventPublisher outbox, ZeloProperties properties, ObjectMapper json) {
        this.requests = requests;
        this.subjectService = subjectService;
        this.auditTrail = auditTrail;
        this.outbox = outbox;
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

        // Queue the signed webhook in the same transaction (transactional outbox):
        // it commits atomically with the request and is delivered after commit.
        String webhookBody = json.createObjectNode()
                .put("requestId", request.getId().toString())
                .put("externalId", externalId)
                .put("deadline", HashChain.formatOccurredAt(deadline))
                .toString();
        outbox.publish(AuditEventType.DSR_DELETE_REQUESTED, webhookBody, Map.of(
                WEBHOOK_HEADER_API_KEY_ID, apiKeyId.toString(),
                WEBHOOK_HEADER_REQUEST_ID, request.getId().toString()));

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

    /**
     * Advance a request to DISPATCHED once its webhook has actually been delivered.
     * Called by the delivery hook; idempotent so an at-least-once redelivery (or a
     * request already fulfilled/dispatched) is a safe no-op.
     */
    @Transactional
    public void markDispatchedIfPending(UUID apiKeyId, UUID requestId) {
        requests.findByIdAndApiKeyId(requestId, apiKeyId).ifPresent(request -> {
            if (request.getStatus() == DsrStatus.RECEIVED) {
                Instant now = Instant.now();
                request.markDispatched(now);
                auditTrail.append(apiKeyId, AuditEventType.DSR_DELETE_DISPATCHED,
                        json.createObjectNode().put("requestId", request.getId().toString()),
                        now);
            }
        });
    }

    private DsrRequest findOwned(UUID apiKeyId, UUID requestId) {
        return requests.findByIdAndApiKeyId(requestId, apiKeyId)
                .orElseThrow(() -> new ResourceNotFoundException("No request with id '" + requestId + "'"));
    }
}
