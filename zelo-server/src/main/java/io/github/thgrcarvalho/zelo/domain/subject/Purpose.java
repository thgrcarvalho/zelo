package io.github.thgrcarvalho.zelo.domain.subject;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * A declared processing purpose with its LGPD legal basis. Mutable aggregate → JPA.
 */
@Entity
@Table(name = "purposes")
public class Purpose {

    @Id
    private UUID id;

    @Column(name = "api_key_id", nullable = false, updatable = false)
    private UUID apiKeyId;

    @Column(name = "key", nullable = false, updatable = false)
    private String key;

    @Column(name = "description", nullable = false)
    private String description;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "legal_basis", nullable = false, columnDefinition = "legal_basis")
    private LegalBasis legalBasis;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Purpose() {
        // for JPA
    }

    public Purpose(UUID id, UUID apiKeyId, String key, String description,
                   LegalBasis legalBasis, Instant createdAt) {
        this.id = id;
        this.apiKeyId = apiKeyId;
        this.key = key;
        this.description = description;
        this.legalBasis = legalBasis;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getApiKeyId() {
        return apiKeyId;
    }

    public String getKey() {
        return key;
    }

    public String getDescription() {
        return description;
    }

    public LegalBasis getLegalBasis() {
        return legalBasis;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
