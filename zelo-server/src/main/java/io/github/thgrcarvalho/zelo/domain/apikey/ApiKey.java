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

    /** Optional billing tier (e.g. {@code internal}, {@code free}, {@code pro}); null until billing exists. */
    @Column(name = "tier")
    private String tier;

    /** Set when the key is revoked; a revoked key no longer authenticates. */
    @Column(name = "revoked_at")
    private Instant revokedAt;

    /**
     * The self-service account that owns this key, or null for bootstrap/operator-
     * minted keys that predate accounts. Scopes account-facing key management.
     */
    @Column(name = "account_id")
    private UUID accountId;

    protected ApiKey() {
        // for JPA
    }

    public ApiKey(UUID id, String keyHash, String name, String webhookUrl,
                  String webhookSecret, Instant createdAt) {
        this(id, keyHash, name, webhookUrl, webhookSecret, null, createdAt);
    }

    public ApiKey(UUID id, String keyHash, String name, String webhookUrl,
                  String webhookSecret, String tier, Instant createdAt) {
        this(id, keyHash, name, webhookUrl, webhookSecret, tier, null, createdAt);
    }

    public ApiKey(UUID id, String keyHash, String name, String webhookUrl,
                  String webhookSecret, String tier, UUID accountId, Instant createdAt) {
        this.id = id;
        this.keyHash = keyHash;
        this.name = name;
        this.webhookUrl = webhookUrl;
        this.webhookSecret = webhookSecret;
        this.tier = tier;
        this.accountId = accountId;
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

    public String getTier() {
        return tier;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public void updateWebhook(String webhookUrl, String webhookSecret) {
        this.webhookUrl = webhookUrl;
        this.webhookSecret = webhookSecret;
    }

    /**
     * Soft-revoke: the key stops authenticating but its rows and audit chain
     * remain. Returns {@code true} only when this call performed the transition
     * (the key was still active), so callers can skip a redundant write.
     */
    public boolean revoke(Instant when) {
        if (revokedAt != null) {
            return false;
        }
        this.revokedAt = when;
        return true;
    }
}
