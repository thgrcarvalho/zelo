package io.github.thgrcarvalho.zelo.infrastructure.bootstrap;

import io.github.thgrcarvalho.zelo.domain.apikey.ApiKey;
import io.github.thgrcarvalho.zelo.domain.apikey.ApiKeyRepository;
import io.github.thgrcarvalho.zelo.domain.crypto.Hashes;
import io.github.thgrcarvalho.zelo.infrastructure.config.ZeloProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Seeds a single API key from {@code zelo.bootstrap.*} when configured, so local
 * dev and the demo have a known key without an out-of-band provisioning step.
 * Idempotent: re-running refreshes the webhook destination/secret for the key.
 */
@Component
public class ApiKeyBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyBootstrap.class);

    private final ApiKeyRepository apiKeys;
    private final ZeloProperties properties;

    public ApiKeyBootstrap(ApiKeyRepository apiKeys, ZeloProperties properties) {
        this.apiKeys = apiKeys;
        this.properties = properties;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        ZeloProperties.Bootstrap config = properties.getBootstrap();
        if (config.getApiKey() == null || config.getApiKey().isBlank()) {
            return;
        }
        String keyHash = Hashes.sha256Hex(config.getApiKey());
        String webhookUrl = blankToNull(config.getWebhookUrl());
        String webhookSecret = blankToNull(config.getWebhookSecret());

        apiKeys.findByKeyHash(keyHash).ifPresentOrElse(existing -> {
            existing.updateWebhook(webhookUrl, webhookSecret);
            apiKeys.save(existing);
            log.info("Bootstrap API key '{}' already present; webhook configuration refreshed", existing.getName());
        }, () -> {
            apiKeys.save(new ApiKey(UUID.randomUUID(), keyHash, config.getName(),
                    webhookUrl, webhookSecret, Instant.now()));
            log.info("Bootstrapped API key '{}'", config.getName());
        });
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}
