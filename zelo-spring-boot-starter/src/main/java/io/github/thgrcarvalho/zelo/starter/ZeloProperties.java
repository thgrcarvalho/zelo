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
}
