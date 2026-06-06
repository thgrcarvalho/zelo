package io.github.thgrcarvalho.zelo.domain.apikey;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One integrator application. The API key (stored only as a SHA-256 hash) scopes
 * every other row, keeping the model forward-compatible with multi-tenancy.
 * Holds the integrator's webhook destination + signing secret.
 */
@Entity
@Table(name = "api_keys")
public class ApiKey {

    @Id
    private UUID id;

    @Column(name = "key_hash", nullable = false, unique = true, updatable = false)
    private String keyHash;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "webhook_url")
    private String webhookUrl;

    @Column(name = "webhook_secret")
    private String webhookSecret;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ApiKey() {
        // for JPA
    }

    public ApiKey(UUID id, String keyHash, String name, String webhookUrl,
                  String webhookSecret, Instant createdAt) {
        this.id = id;
        this.keyHash = keyHash;
        this.name = name;
        this.webhookUrl = webhookUrl;
        this.webhookSecret = webhookSecret;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public String getKeyHash() {
        return keyHash;
    }

    public String getName() {
        return name;
    }

    public String getWebhookUrl() {
        return webhookUrl;
    }

    public String getWebhookSecret() {
        return webhookSecret;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void updateWebhook(String webhookUrl, String webhookSecret) {
        this.webhookUrl = webhookUrl;
        this.webhookSecret = webhookSecret;
    }
}
