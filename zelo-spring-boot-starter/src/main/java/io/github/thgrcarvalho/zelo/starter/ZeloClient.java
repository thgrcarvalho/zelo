package io.github.thgrcarvalho.zelo.starter;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Client for the Zelo control plane's {@code /v1} REST API. Covers the full LGPD
 * surface an integrator needs: declaring {@link ZeloPurpose purposes}, registering
 * {@link ZeloSubject subjects}, recording and querying consent, opening and
 * fulfilling deletion requests, and verifying the audit chain.
 *
 * <p><strong>Host-independent JSON.</strong> Zelo speaks {@code snake_case}. This
 * client carries its own {@code snake_case} {@link ObjectMapper}, so it
 * (de)serialises correctly regardless of how the <em>host</em> application has
 * configured Jackson (e.g. an app left on the default {@code camelCase}). It never
 * touches the host's mapper.</p>
 *
 * <p>Server errors surface as {@link org.springframework.web.client.RestClientResponseException}
 * (e.g. {@link HttpClientErrorException.NotFound} for an unknown subject/request),
 * except where a method documents that it absorbs a specific status.</p>
 */
public class ZeloClient {

    private static final Logger log = LoggerFactory.getLogger(ZeloClient.class);

    private static final ParameterizedTypeReference<List<ZeloPurpose>> PURPOSE_LIST =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<List<ZeloAuditEntry>> AUDIT_LIST =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient http;
    private final String apiUrl;
    private final String apiKey;

    public ZeloClient(RestClient.Builder builder, ZeloProperties properties) {
        this.apiUrl = trimTrailingSlash(properties.getApiUrl());
        this.apiKey = properties.getApiKey();

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(10_000);

        // A dedicated snake_case mapper isolates the wire format from the host app's
        // Jackson config. Jackson2ObjectMapperBuilder already registers JavaTime and
        // disables FAIL_ON_UNKNOWN_PROPERTIES / WRITE_DATES_AS_TIMESTAMPS.
        ObjectMapper mapper = Jackson2ObjectMapperBuilder.json()
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .serializationInclusion(JsonInclude.Include.NON_NULL)
                .build();

        this.http = builder
                .requestFactory(factory)
                .messageConverters(converters -> {
                    converters.removeIf(c -> c instanceof MappingJackson2HttpMessageConverter);
                    converters.add(0, new MappingJackson2HttpMessageConverter(mapper));
                })
                .build();
    }

    private static final int MAX_ATTEMPTS = 3;

    /**
     * Run a write with bounded retry on <em>transient</em> failures — connect/read
     * timeouts and 5xx — with a short linear backoff. A 4xx is deterministic and never
     * retried. State-mutating callers pass a stable {@code Idempotency-Key} so a retried
     * attempt is deduplicated server-side rather than double-applied.
     */
    private <T> T retrying(Supplier<T> call) {
        RestClientException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return call.get();
            } catch (HttpClientErrorException clientError) {
                throw clientError;   // 4xx — deterministic, don't retry
            } catch (RestClientException transientError) {
                last = transientError;   // 5xx or transport (connect/read timeout)
                if (attempt < MAX_ATTEMPTS) {
                    try {
                        Thread.sleep(200L * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw transientError;
                    }
                }
            }
        }
        throw last;
    }

    // ---------------------------------------------------------------- subjects

    /**
     * Register (upsert) a subject by {@code externalId}. Idempotent: Zelo returns the
     * existing subject if it already knows the id. Recording consent or opening a
     * request also registers the subject implicitly, so calling this is optional.
     */
    public ZeloSubject registerSubject(String externalId) {
        String idempotencyKey = "subject-" + UUID.randomUUID();
        return retrying(() -> http.post()
                .uri(apiUrl + "/v1/subjects")
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("external_id", externalId))
                .retrieve()
                .body(ZeloSubject.class));
    }

    // --------------------------------------------------------------- purposes

    /**
     * Declare a processing purpose with its LGPD legal basis. <strong>Idempotent:</strong>
     * if a purpose with {@code key} already exists, the existing definition is returned
     * unchanged (its description/legal basis are not modified), so this is safe to call
     * on every startup.
     */
    public ZeloPurpose definePurpose(String key, String description, ZeloLegalBasis legalBasis) {
        try {
            String idempotencyKey = "purpose-" + UUID.randomUUID();
            return retrying(() -> http.post()
                    .uri(apiUrl + "/v1/purposes")
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header("Idempotency-Key", idempotencyKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("key", key, "description", description, "legal_basis", legalBasis))
                    .retrieve()
                    .body(ZeloPurpose.class));
        } catch (HttpClientErrorException.Conflict alreadyExists) {
            log.debug("Purpose '{}' already declared; returning the existing definition", key);
            return listPurposes().stream()
                    .filter(p -> key.equals(p.key()))
                    .findFirst()
                    .orElseThrow(() -> alreadyExists);
        }
    }

    /** List every purpose declared for this integrator. */
    public List<ZeloPurpose> listPurposes() {
        return http.get()
                .uri(apiUrl + "/v1/purposes")
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .retrieve()
                .body(PURPOSE_LIST);
    }

    // ---------------------------------------------------------------- consent

    /**
     * Record a consent decision (append-only) and return the resulting state + history.
     * The {@code purposeKey} must reference a purpose declared via {@link #definePurpose}.
     * {@code source} and {@code metadata} are optional and must be PII-free;
     * {@code metadata} is folded into the tamper-evident audit payload.
     */
    public ZeloConsentReport recordConsent(String externalId, String purposeKey, ZeloConsentAction action,
                                           String source, Map<String, Object> metadata) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("external_id", externalId);
        body.put("purpose_key", purposeKey);
        body.put("action", action);
        if (source != null) {
            body.put("source", source);
        }
        if (metadata != null && !metadata.isEmpty()) {
            body.put("metadata", metadata);
        }
        String idempotencyKey = "consent-" + UUID.randomUUID();
        return retrying(() -> http.post()
                .uri(apiUrl + "/v1/consents")
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(ZeloConsentReport.class));
    }

    /** Grant consent for a purpose. Shorthand for {@link #recordConsent}. */
    public ZeloConsentReport grantConsent(String externalId, String purposeKey, String source) {
        return recordConsent(externalId, purposeKey, ZeloConsentAction.GRANT, source, null);
    }

    /** Withdraw a previously granted consent (LGPD Art. 8 §5). Shorthand for {@link #recordConsent}. */
    public ZeloConsentReport withdrawConsent(String externalId, String purposeKey, String source) {
        return recordConsent(externalId, purposeKey, ZeloConsentAction.WITHDRAW, source, null);
    }

    /** The full consent picture for a subject. Throws {@code NotFound} if the subject is unknown. */
    public ZeloConsentReport getConsent(String externalId) {
        return http.get()
                .uri(apiUrl + "/v1/consents?subject={s}", externalId)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .retrieve()
                .body(ZeloConsentReport.class);
    }

    /** The consent picture for a subject, narrowed to one purpose. */
    public ZeloConsentReport getConsent(String externalId, String purposeKey) {
        return http.get()
                .uri(apiUrl + "/v1/consents?subject={s}&purpose={p}", externalId, purposeKey)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .retrieve()
                .body(ZeloConsentReport.class);
    }

    /**
     * Whether consent for {@code purposeKey} is currently granted. An unknown subject
     * counts as not granted (so this never throws for a never-seen user) — convenient
     * for gating a feature behind consent.
     */
    public boolean isGranted(String externalId, String purposeKey) {
        try {
            ZeloConsentReport report = getConsent(externalId, purposeKey);
            return report != null && report.isGranted(purposeKey);
        } catch (HttpClientErrorException.NotFound neverRegistered) {
            return false;
        }
    }

    // -------------------------------------------------------------------- DSR

    /**
     * Open a DELETE data-subject request at Zelo for {@code externalId}. Zelo will fire
     * the {@code dsr.delete.requested} webhook back to this app. Returns the freshly
     * created request (status {@code RECEIVED}, with its deadline) — use
     * {@link ZeloRequest#id()} to correlate the later webhook and fulfillment.
     */
    public ZeloRequest requestDeletion(String externalId) {
        return http.post()
                .uri(apiUrl + "/v1/requests")
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("external_id", externalId, "type", "DELETE"))
                .retrieve()
                .body(ZeloRequest.class);
    }

    /** The current state of a request — status, deadline countdown, fulfillment proof. */
    public ZeloRequest getRequest(String requestId) {
        return http.get()
                .uri(apiUrl + "/v1/requests/{id}", requestId)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .retrieve()
                .body(ZeloRequest.class);
    }

    /**
     * Mark a request fulfilled, attaching {@code proof}. Sends an idempotency key
     * derived from the request id and treats a 409 (already fulfilled) as success,
     * so an at-least-once webhook redelivery is safe.
     */
    public void fulfill(String requestId, Object proof) {
        try {
            retrying(() -> http.post()
                    .uri(apiUrl + "/v1/requests/{id}/fulfill", requestId)
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header("Idempotency-Key", "fulfill-" + requestId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("proof", proof == null ? Map.of() : proof))
                    .retrieve()
                    .toBodilessEntity());
        } catch (HttpClientErrorException.Conflict e) {
            log.info("Zelo request {} was already fulfilled; treating as success", requestId);
        }
    }

    // ------------------------------------------------------------------ audit

    /** Recompute and verify this integrator's tamper-evident audit chain. */
    public ZeloAuditVerification verifyAudit() {
        return http.get()
                .uri(apiUrl + "/v1/audit/verify")
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .retrieve()
                .body(ZeloAuditVerification.class);
    }

    /** Export up to {@code limit} audit entries (Zelo caps at 1000), oldest first. */
    public List<ZeloAuditEntry> exportAudit(int limit) {
        return http.get()
                .uri(apiUrl + "/v1/audit?limit={n}", limit)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .retrieve()
                .body(AUDIT_LIST);
    }

    private static String trimTrailingSlash(String url) {
        if (url != null && url.endsWith("/")) {
            return url.substring(0, url.length() - 1);
        }
        return url;
    }
}
