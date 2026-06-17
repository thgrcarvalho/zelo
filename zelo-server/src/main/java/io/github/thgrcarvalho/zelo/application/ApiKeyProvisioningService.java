package io.github.thgrcarvalho.zelo.application;

import io.github.thgrcarvalho.zelo.application.error.ResourceNotFoundException;
import io.github.thgrcarvalho.zelo.domain.apikey.ApiKey;
import io.github.thgrcarvalho.zelo.domain.apikey.ApiKeyRepository;
import io.github.thgrcarvalho.zelo.domain.crypto.Hashes;
import io.github.thgrcarvalho.zelo.domain.crypto.RawKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Provisions client API keys at runtime — the operator-facing equivalent of the
 * config-driven {@link io.github.thgrcarvalho.zelo.infrastructure.bootstrap.ApiKeyBootstrap}.
 * Mints an opaque key, stores only its SHA-256 hash, and returns the raw value
 * exactly once. Keys are soft-revoked so the tenant's rows and tamper-evident
 * audit chain survive (a hard delete would cascade them away).
 */
@Service
public class ApiKeyProvisioningService {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyProvisioningService.class);

    private final ApiKeyRepository apiKeys;

    public ApiKeyProvisioningService(ApiKeyRepository apiKeys) {
        this.apiKeys = apiKeys;
    }

    /** Mint a new key for {@code name}; the returned raw value is shown only here. */
    @Transactional
    public Minted create(String name, String webhookUrl, String webhookSecret, String tier) {
        String rawKey = RawKeys.generate();
        ApiKey apiKey = new ApiKey(UUID.randomUUID(), Hashes.sha256Hex(rawKey), name,
                blankToNull(webhookUrl), blankToNull(webhookSecret), blankToNull(tier), Instant.now());
        apiKeys.save(apiKey);
        log.info("Provisioned API key '{}' (id={}, tier={})", name, apiKey.getId(), apiKey.getTier());
        return new Minted(apiKey, rawKey);
    }

    @Transactional(readOnly = true)
    public List<ApiKey> list() {
        return apiKeys.findAll();
    }

    /**
     * Set a key's webhook destination + signing secret (e.g. to enable
     * deletion-orchestration delivery once the client's public URL is known).
     * Replaces both fields. Returns the updated key.
     */
    @Transactional
    public ApiKey updateWebhook(UUID id, String webhookUrl, String webhookSecret) {
        ApiKey apiKey = apiKeys.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("API key " + id + " not found"));
        apiKey.updateWebhook(blankToNull(webhookUrl), blankToNull(webhookSecret));
        apiKeys.save(apiKey);
        log.info("Updated webhook for API key id={} (name='{}')", apiKey.getId(), apiKey.getName());
        return apiKey;
    }

    /** Soft-revoke a key by id; idempotent. The key stops authenticating at once. */
    @Transactional
    public void revoke(UUID id) {
        ApiKey apiKey = apiKeys.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("API key " + id + " not found"));
        if (apiKey.revoke(Instant.now())) {
            apiKeys.save(apiKey);
            log.info("Revoked API key id={} (name='{}')", apiKey.getId(), apiKey.getName());
        }
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }

    /** A freshly minted key plus its raw value, which is exposed only at creation. */
    public record Minted(ApiKey apiKey, String rawKey) {
    }
}
