package io.github.thgrcarvalho.zelo.infrastructure.webhook;

import io.github.thgrcarvalho.outbox.OutboxEvent;
import io.github.thgrcarvalho.outbox.OutboxPublisher;
import io.github.thgrcarvalho.zelo.application.DsrService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Delivers queued webhook events to integrators. Registered as the outbox
 * starter's {@link OutboxPublisher}; the starter's poller invokes it for each
 * pending event and retries on any thrown exception.
 *
 * <p>For each event it looks up the integrator's webhook URL + secret (by the
 * api-key id carried in the event headers), signs the raw body with
 * {@link WebhookSigner}, and POSTs it. A non-2xx response (or a transport error)
 * throws, so the poller retries with backoff. On success it advances the DSR
 * RECEIVED → DISPATCHED (idempotently — a redelivery is a no-op).</p>
 */
@Component
public class ZeloWebhookPublisher implements OutboxPublisher {

    static final String SIGNATURE_HEADER = "X-Zelo-Signature";
    static final String EVENT_HEADER = "X-Zelo-Event";

    private static final Logger log = LoggerFactory.getLogger(ZeloWebhookPublisher.class);

    private final JdbcTemplate jdbc;
    private final DsrService dsrService;
    private final RestClient restClient;

    public ZeloWebhookPublisher(JdbcTemplate jdbc, DsrService dsrService, RestClient.Builder restClientBuilder) {
        this.jdbc = jdbc;
        this.dsrService = dsrService;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(10_000);
        this.restClient = restClientBuilder.requestFactory(factory).build();
    }

    @Override
    public void publish(OutboxEvent event) throws Exception {
        String apiKeyIdRaw = event.headers().get(DsrService.WEBHOOK_HEADER_API_KEY_ID);
        if (apiKeyIdRaw == null) {
            log.warn("Outbox event {} has no {} header; cannot deliver",
                    event.eventType(), DsrService.WEBHOOK_HEADER_API_KEY_ID);
            return;
        }
        UUID apiKeyId = UUID.fromString(apiKeyIdRaw);
        WebhookTarget target = loadTarget(apiKeyId);
        if (target == null || target.url() == null || target.url().isBlank()) {
            log.warn("Integrator {} has no webhook URL configured; dropping event {}", apiKeyId, event.eventType());
            return;
        }
        if (target.secret() == null || target.secret().isBlank()) {
            // Fail closed: never send an unsigned (empty-key) webhook. Dropping the
            // event lets the request go OVERDUE, surfacing the misconfiguration.
            log.error("Integrator {} has a webhook URL but no signing secret; refusing to send {}",
                    apiKeyId, event.eventType());
            return;
        }

        byte[] body = event.payload().getBytes(StandardCharsets.UTF_8);
        String signature = WebhookSigner.sign(target.secret(), body);

        restClient.post()
                .uri(target.url())
                .header(SIGNATURE_HEADER, signature)
                .header(EVENT_HEADER, event.eventType())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity();   // throws on non-2xx → the poller retries

        log.info("Delivered {} to {}", event.eventType(), target.url());

        String requestIdRaw = event.headers().get(DsrService.WEBHOOK_HEADER_REQUEST_ID);
        if (requestIdRaw != null) {
            try {
                dsrService.markDispatchedIfPending(apiKeyId, UUID.fromString(requestIdRaw));
            } catch (RuntimeException e) {
                // Best-effort: the webhook is already delivered. A concurrent fulfill
                // (optimistic conflict) or transient error must not provoke a redelivery.
                log.warn("Delivered {} but could not mark request {} dispatched: {}",
                        event.eventType(), requestIdRaw, e.toString());
            }
        }
    }

    private WebhookTarget loadTarget(UUID apiKeyId) {
        return jdbc.query(
                "SELECT webhook_url, webhook_secret FROM api_keys WHERE id = ?",
                rs -> rs.next() ? new WebhookTarget(rs.getString(1), rs.getString(2)) : null,
                apiKeyId);
    }

    private record WebhookTarget(String url, String secret) {
    }
}
