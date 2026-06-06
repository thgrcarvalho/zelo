package io.github.thgrcarvalho.zelo.domain.dsr;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * A data-subject request and its lifecycle. Mutable aggregate → JPA. The status
 * transitions are guarded here so an invalid move is rejected by the domain, not
 * just by a controller.
 */
@Entity
@Table(name = "dsr_requests")
public class DsrRequest {

    @Id
    private UUID id;

    @Column(name = "api_key_id", nullable = false, updatable = false)
    private UUID apiKeyId;

    @Column(name = "subject_id", nullable = false, updatable = false)
    private UUID subjectId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "type", nullable = false, updatable = false, columnDefinition = "dsr_type")
    private DsrType type;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "status", nullable = false, columnDefinition = "dsr_status")
    private DsrStatus status;

    @Column(name = "deadline_at", nullable = false, updatable = false)
    private Instant deadlineAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "dispatched_at")
    private Instant dispatchedAt;

    @Column(name = "fulfilled_at")
    private Instant fulfilledAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "fulfillment_proof", columnDefinition = "jsonb")
    private Map<String, Object> fulfillmentProof;

    protected DsrRequest() {
        // for JPA
    }

    private DsrRequest(UUID id, UUID apiKeyId, UUID subjectId, DsrType type, DsrStatus status,
                       Instant deadlineAt, Instant createdAt) {
        this.id = id;
        this.apiKeyId = apiKeyId;
        this.subjectId = subjectId;
        this.type = type;
        this.status = status;
        this.deadlineAt = deadlineAt;
        this.createdAt = createdAt;
    }

    /** A newly received DELETE request. */
    public static DsrRequest received(UUID id, UUID apiKeyId, UUID subjectId,
                                      Instant deadlineAt, Instant createdAt) {
        return new DsrRequest(id, apiKeyId, subjectId, DsrType.DELETE, DsrStatus.RECEIVED, deadlineAt, createdAt);
    }

    /** RECEIVED → DISPATCHED, once the webhook has been handed to the outbox. */
    public void markDispatched(Instant at) {
        if (status != DsrStatus.RECEIVED) {
            throw new InvalidDsrTransitionException(
                    "Only a RECEIVED request can be dispatched; this one is " + status);
        }
        this.status = DsrStatus.DISPATCHED;
        this.dispatchedAt = at;
    }

    /** RECEIVED/DISPATCHED/OVERDUE → FULFILLED. A late (OVERDUE) fulfillment is allowed. */
    public void markFulfilled(Instant at, Map<String, Object> proof) {
        if (status == DsrStatus.FULFILLED) {
            throw new InvalidDsrTransitionException("Request is already fulfilled");
        }
        this.status = DsrStatus.FULFILLED;
        this.fulfilledAt = at;
        this.fulfillmentProof = proof;
    }

    /** RECEIVED/DISPATCHED → OVERDUE, when the deadline passes unfulfilled. */
    public void markOverdue() {
        if (status != DsrStatus.RECEIVED && status != DsrStatus.DISPATCHED) {
            throw new InvalidDsrTransitionException(
                    "Only an open (RECEIVED/DISPATCHED) request can become OVERDUE; this one is " + status);
        }
        this.status = DsrStatus.OVERDUE;
    }

    public UUID getId() {
        return id;
    }

    public UUID getApiKeyId() {
        return apiKeyId;
    }

    public UUID getSubjectId() {
        return subjectId;
    }

    public DsrType getType() {
        return type;
    }

    public DsrStatus getStatus() {
        return status;
    }

    public Instant getDeadlineAt() {
        return deadlineAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getDispatchedAt() {
        return dispatchedAt;
    }

    public Instant getFulfilledAt() {
        return fulfilledAt;
    }

    public Map<String, Object> getFulfillmentProof() {
        return fulfillmentProof;
    }
}
