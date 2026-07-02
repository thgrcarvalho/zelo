package io.github.thgrcarvalho.zelo.infrastructure.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Zelo's own configuration, under the {@code zelo.*} prefix. */
@ConfigurationProperties(prefix = "zelo")
@Validated
public class ZeloProperties {

    private final Dsr dsr = new Dsr();
    private final Bootstrap bootstrap = new Bootstrap();
    private final Admin admin = new Admin();
    private final Auth auth = new Auth();
    private final Mail mail = new Mail();
    private final Showcase showcase = new Showcase();
    private final Plans plans = new Plans();
    private final Billing billing = new Billing();

    public Dsr getDsr() {
        return dsr;
    }

    public Bootstrap getBootstrap() {
        return bootstrap;
    }

    public Admin getAdmin() {
        return admin;
    }

    public Auth getAuth() {
        return auth;
    }

    public Mail getMail() {
        return mail;
    }

    public Showcase getShowcase() {
        return showcase;
    }

    public Plans getPlans() {
        return plans;
    }

    public Billing getBilling() {
        return billing;
    }

    /**
     * Payment-provider (Asaas) settings. Everything is fail-closed while unset:
     * a blank api key disables checkout (503), a blank webhook token rejects
     * every webhook delivery (503).
     */
    public static class Billing {

        /** Asaas API key. Blank = billing disabled. */
        private String asaasApiKey = "";

        /** Asaas REST base; switch to https://api-sandbox.asaas.com/v3 for sandbox. */
        private String asaasBaseUrl = "https://api.asaas.com/v3";

        /** Shared secret Asaas sends back in the asaas-access-token webhook header. */
        private String webhookToken = "";

        /** Monthly PRO price in BRL. */
        private String proPriceBrl = "79.00";

        public boolean isEnabled() {
            return asaasApiKey != null && !asaasApiKey.isBlank();
        }

        public String getAsaasApiKey() {
            return asaasApiKey;
        }

        public void setAsaasApiKey(String asaasApiKey) {
            this.asaasApiKey = asaasApiKey;
        }

        public String getAsaasBaseUrl() {
            return asaasBaseUrl;
        }

        public void setAsaasBaseUrl(String asaasBaseUrl) {
            this.asaasBaseUrl = asaasBaseUrl;
        }

        public String getWebhookToken() {
            return webhookToken;
        }

        public void setWebhookToken(String webhookToken) {
            this.webhookToken = webhookToken;
        }

        public String getProPriceBrl() {
            return proPriceBrl;
        }

        public void setProPriceBrl(String proPriceBrl) {
            this.proPriceBrl = proPriceBrl;
        }
    }

    /**
     * Free-tier ceilings and the hard-cap multiplier. Ceilings drive the 80%/100%
     * usage-alert emails; a /v1 write is refused (429) only past ceiling × multiplier.
     * PRO accounts and keys without an account are never metered.
     */
    public static class Plans {

        private final Free free = new Free();

        /** A write is hard-refused only beyond ceiling × this. */
        @Min(1)
        private int hardCapMultiplier = 3;

        public Free getFree() {
            return free;
        }

        public int getHardCapMultiplier() {
            return hardCapMultiplier;
        }

        public void setHardCapMultiplier(int hardCapMultiplier) {
            this.hardCapMultiplier = hardCapMultiplier;
        }

        public static class Free {

            @Min(1)
            private long subjectsPerMonth = 500;

            @Min(1)
            private long auditEventsPerMonth = 2000;

            @Min(1)
            private int apiKeys = 3;

            public long getSubjectsPerMonth() {
                return subjectsPerMonth;
            }

            public void setSubjectsPerMonth(long subjectsPerMonth) {
                this.subjectsPerMonth = subjectsPerMonth;
            }

            public long getAuditEventsPerMonth() {
                return auditEventsPerMonth;
            }

            public void setAuditEventsPerMonth(long auditEventsPerMonth) {
                this.auditEventsPerMonth = auditEventsPerMonth;
            }

            public int getApiKeys() {
                return apiKeys;
            }

            public void setApiKeys(int apiKeys) {
                this.apiKeys = apiKeys;
            }
        }
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

    /**
     * Self-service account auth ({@code /account/**}). {@code session-secret} blank →
     * session auth fails closed (no dashboard logins). Onboarding is instant +
     * email-gated (see {@link Mail}); there is no operator to seed.
     */
    public static class Auth {
        /** HMAC key for signing session cookies. Blank disables /account auth (fail-closed). */
        private String sessionSecret;
        /** Session lifetime, in hours (default 7 days; bounded 1h–1y so a misconfig can't mint already-expired or absurd sessions). */
        @Min(1)
        @Max(8760)
        private int sessionTtlHours = 168;
        /** Consecutive failed logins before an account is temporarily locked (brute-force backstop). */
        @Min(3)
        @Max(100)
        private int loginMaxFailures = 10;
        /** How long an account stays locked after crossing the failure threshold, minutes. */
        @Min(1)
        @Max(1440)
        private int loginLockoutMinutes = 15;

        public String getSessionSecret() {
            return sessionSecret;
        }

        public void setSessionSecret(String sessionSecret) {
            this.sessionSecret = sessionSecret;
        }

        public int getSessionTtlHours() {
            return sessionTtlHours;
        }

        public void setSessionTtlHours(int sessionTtlHours) {
            this.sessionTtlHours = sessionTtlHours;
        }

        public int getLoginMaxFailures() {
            return loginMaxFailures;
        }

        public void setLoginMaxFailures(int loginMaxFailures) {
            this.loginMaxFailures = loginMaxFailures;
        }

        public int getLoginLockoutMinutes() {
            return loginLockoutMinutes;
        }

        public void setLoginLockoutMinutes(int loginLockoutMinutes) {
            this.loginLockoutMinutes = loginLockoutMinutes;
        }
    }

    /**
     * Transactional email for verification + password reset ({@code zelo.mail.*}).
     * {@code enabled=false} (default) uses a logging no-op sender; with
     * {@code require-verification=true} that makes signup fail closed (503) rather
     * than activate an unverified account. The standard {@code spring.mail.*} keys
     * drive the SMTP transport (Resend by default).
     */
    public static class Mail {
        /** Master switch; false ⇒ the logging no-op sender (no real mail). */
        private boolean enabled = false;
        /** From-address, e.g. no-reply@zelocompliance.com. Required when enabled. */
        private String from;
        /** Optional Reply-To. */
        private String replyTo;
        /** Absolute https base for verify/reset links (no path), e.g. https://zelocompliance.com. Required when enabled. */
        private String baseUrl;
        /** When true, signup creates an UNVERIFIED account + sends a verify email; fail-closed if mail is off. */
        private boolean requireVerification = true;
        /** Verification-link lifetime, hours. */
        @Min(1)
        @Max(72)
        private int verificationTtlHours = 24;
        /** Password-reset-link lifetime, minutes (kept short). */
        @Min(5)
        @Max(240)
        private int resetTtlMinutes = 30;
        /** Minimum gap between resend / reset-request emails to one account, seconds. */
        @Min(15)
        @Max(86400)
        private int resendCooldownSeconds = 60;
        /** Per-account cap on verification/reset emails in a rolling 24h (anti mailbox-bomb). */
        @Min(1)
        @Max(100)
        private int dailyEmailsPerAccount = 5;
        /**
         * How long a spent (expired) token row is retained before the purge job may
         * delete it. The {@code @Min(24)} floor is load-bearing, not cosmetic: the
         * abuse windows still count a token by {@code created_at}, so retention must
         * stay ≥ both of them — the cooldown ({@code resend-cooldown-seconds}, max
         * 86400s = 24h) and the daily cap (a hardcoded rolling 24h). At 24h the purge
         * ({@code created_at < now - 24h}) and the windows ({@code created_at > now -
         * 24h}) meet at a boundary they each exclude, so no counted row is ever
         * dropped. Do not lower the floor below 24h without raising both windows. The
         * default leaves a generous margin.
         */
        @Min(24)
        @Max(8760)
        private int tokenRetentionHours = 48;
        /** How often the stale-token purge runs, in milliseconds (default 1h). */
        @Min(60_000)
        private long tokenPurgeIntervalMs = 3_600_000;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getFrom() {
            return from;
        }

        public void setFrom(String from) {
            this.from = from;
        }

        public String getReplyTo() {
            return replyTo;
        }

        public void setReplyTo(String replyTo) {
            this.replyTo = replyTo;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public boolean isRequireVerification() {
            return requireVerification;
        }

        public void setRequireVerification(boolean requireVerification) {
            this.requireVerification = requireVerification;
        }

        public int getVerificationTtlHours() {
            return verificationTtlHours;
        }

        public void setVerificationTtlHours(int verificationTtlHours) {
            this.verificationTtlHours = verificationTtlHours;
        }

        public int getResetTtlMinutes() {
            return resetTtlMinutes;
        }

        public void setResetTtlMinutes(int resetTtlMinutes) {
            this.resetTtlMinutes = resetTtlMinutes;
        }

        public int getResendCooldownSeconds() {
            return resendCooldownSeconds;
        }

        public void setResendCooldownSeconds(int resendCooldownSeconds) {
            this.resendCooldownSeconds = resendCooldownSeconds;
        }

        public int getDailyEmailsPerAccount() {
            return dailyEmailsPerAccount;
        }

        public void setDailyEmailsPerAccount(int dailyEmailsPerAccount) {
            this.dailyEmailsPerAccount = dailyEmailsPerAccount;
        }

        public int getTokenRetentionHours() {
            return tokenRetentionHours;
        }

        public void setTokenRetentionHours(int tokenRetentionHours) {
            this.tokenRetentionHours = tokenRetentionHours;
        }

        public long getTokenPurgeIntervalMs() {
            return tokenPurgeIntervalMs;
        }

        public void setTokenPurgeIntervalMs(long tokenPurgeIntervalMs) {
            this.tokenPurgeIntervalMs = tokenPurgeIntervalMs;
        }
    }

    /**
     * The public demo audit chain behind the landing page's live proof widget
     * ({@code GET /v1/audit/verify/demo}). When {@code enabled=false} the synthetic
     * chain is not seeded and the endpoint reports an empty (trivially valid) chain.
     */
    public static class Showcase {
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
