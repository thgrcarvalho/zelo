package io.github.thgrcarvalho.zelo.domain.subject;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A data subject, referenced only by the integrator's opaque {@code externalId}.
 * Holds no PII. Mutable aggregate → JPA.
 */
@Entity
@Table(name = "subjects")
public class Subject {

    @Id
    private UUID id;

    @Column(name = "api_key_id", nullable = false, updatable = false)
    private UUID apiKeyId;

    @Column(name = "external_id", nullable = false, updatable = false)
    private String externalId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Subject() {
        // for JPA
    }

    public Subject(UUID id, UUID apiKeyId, String externalId, Instant createdAt) {
        this.id = id;
        this.apiKeyId = apiKeyId;
        this.externalId = externalId;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getApiKeyId() {
        return apiKeyId;
    }

    public String getExternalId() {
        return externalId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
