package io.github.thgrcarvalho.zelo.starter;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Integrator-side configuration for the Zelo starter.
 *
 * <pre>{@code
 * zelo:
 *   api-url: https://zelo.example.com   # the Zelo control plane
 *   api-key: zk_live_...                 # this integrator's Zelo API key
 *   webhook-secret: whsec_...            # shared secret to verify incoming webhooks
 *   webhook-path: /zelo/webhooks         # where the receiver is mounted (default)
 * }</pre>
 */
@ConfigurationProperties(prefix = "zelo")
public class ZeloProperties {

    private String apiUrl;
    private String apiKey;
    private String webhookSecret;
    private String webhookPath = "/zelo/webhooks";

    /**
     * How much clock difference to tolerate between Zelo stamping a webhook
     * ({@code sentAt}) and this app receiving it, in seconds. A delivery older —
     * or further in the future — than this is rejected as a possible replay; Zelo
     * re-sends with a fresh timestamp, so a transient skew self-heals.
     */
    private long webhookToleranceSeconds = 300;

    public String getApiUrl() {
        return apiUrl;
    }

    public void setApiUrl(String apiUrl) {
        this.apiUrl = apiUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getWebhookSecret() {
        return webhookSecret;
    }

    public void setWebhookSecret(String webhookSecret) {
        this.webhookSecret = webhookSecret;
    }

    public String getWebhookPath() {
        return webhookPath;
    }

    public void setWebhookPath(String webhookPath) {
        this.webhookPath = webhookPath;
    }

    public long getWebhookToleranceSeconds() {
        return webhookToleranceSeconds;
    }

    public void setWebhookToleranceSeconds(long webhookToleranceSeconds) {
        this.webhookToleranceSeconds = webhookToleranceSeconds;
    }
}
