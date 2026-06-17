package io.github.thgrcarvalho.zelo.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Zelo's own configuration, under the {@code zelo.*} prefix. */
@ConfigurationProperties(prefix = "zelo")
public class ZeloProperties {

    private final Dsr dsr = new Dsr();
    private final Bootstrap bootstrap = new Bootstrap();
    private final Admin admin = new Admin();

    public Dsr getDsr() {
        return dsr;
    }

    public Bootstrap getBootstrap() {
        return bootstrap;
    }

    public Admin getAdmin() {
        return admin;
    }

    /** Data-subject-request settings. */
    public static class Dsr {
        /**
         * Days allowed to fulfill a deletion request before it is flagged OVERDUE.
         * LGPD fixes no single universal number; Art. 19 §3's 15-day window for
         * access requests is adopted as the default SLA.
         */
        private int deleteDeadlineDays = 15;

        /** How often the overdue sweep runs, in milliseconds (default 60s). */
        private long overdueSweepIntervalMs = 60_000;

        public int getDeleteDeadlineDays() {
            return deleteDeadlineDays;
        }

        public void setDeleteDeadlineDays(int deleteDeadlineDays) {
            this.deleteDeadlineDays = deleteDeadlineDays;
        }

        public long getOverdueSweepIntervalMs() {
            return overdueSweepIntervalMs;
        }

        public void setOverdueSweepIntervalMs(long overdueSweepIntervalMs) {
            this.overdueSweepIntervalMs = overdueSweepIntervalMs;
        }
    }

    /**
     * Optional bootstrap of a single API key on startup (for local dev and the
     * demo). Leave {@code api-key} blank in production and provision keys
     * out-of-band.
     */
    public static class Bootstrap {
        private String apiKey;
        private String name = "default";
        private String webhookUrl;
        private String webhookSecret;

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getWebhookUrl() {
            return webhookUrl;
        }

        public void setWebhookUrl(String webhookUrl) {
            this.webhookUrl = webhookUrl;
        }

        public String getWebhookSecret() {
            return webhookSecret;
        }

        public void setWebhookSecret(String webhookSecret) {
            this.webhookSecret = webhookSecret;
        }
    }

    /**
     * Master credential guarding the runtime key-provisioning API
     * ({@code /admin/**}). Leave {@code master-key} blank to disable the admin
     * API entirely (fail-closed); set a strong value to enable onboarding.
     */
    public static class Admin {
        private String masterKey;

        public String getMasterKey() {
            return masterKey;
        }

        public void setMasterKey(String masterKey) {
            this.masterKey = masterKey;
        }
    }
}
