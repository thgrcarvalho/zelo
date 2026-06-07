package io.github.thgrcarvalho.zelo.starter;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Thin client for calling back into the Zelo control plane. v1 needs only the
 * fulfill callback.
 */
public class ZeloClient {

    private static final Logger log = LoggerFactory.getLogger(ZeloClient.class);

    private final RestClient http;
    private final String apiUrl;
    private final String apiKey;

    public ZeloClient(RestClient.Builder builder, ZeloProperties properties) {
        this.apiUrl = properties.getApiUrl();
        this.apiKey = properties.getApiKey();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(10_000);
        this.http = builder.requestFactory(factory).build();
    }

    /**
     * Open a DELETE data-subject request at Zelo for {@code externalId}. Zelo will
     * fire the {@code dsr.delete.requested} webhook back to this app. Returns the
     * new request's id.
     */
    public String requestDeletion(String externalId) {
        JsonNode response = http.post()
                .uri(apiUrl + "/v1/requests")
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("external_id", externalId, "type", "DELETE"))
                .retrieve()
                .body(JsonNode.class);
        return response == null ? null : response.path("id").asText(null);
    }

    /**
     * Mark a request fulfilled, attaching {@code proof}. Sends an idempotency key
     * derived from the request id and treats a 409 (already fulfilled) as success,
     * so an at-least-once webhook redelivery is safe.
     */
    public void fulfill(String requestId, Object proof) {
        try {
            http.post()
                    .uri(apiUrl + "/v1/requests/{id}/fulfill", requestId)
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header("Idempotency-Key", "fulfill-" + requestId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("proof", proof == null ? Map.of() : proof))
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException.Conflict e) {
            log.info("Zelo request {} was already fulfilled; treating as success", requestId);
        }
    }
}
