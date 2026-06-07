package io.github.thgrcarvalho.zelo.starter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.thgrcarvalho.pixwebhook.PixWebhookRequest;
import io.github.thgrcarvalho.pixwebhook.PixWebhookValidationException;
import io.github.thgrcarvalho.pixwebhook.PixWebhookValidator;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * Receives Zelo webhooks, verifies the {@code X-Zelo-Signature} (HMAC-SHA256 over
 * the raw body, via pix-webhook-validator), dispatches to the matching
 * {@link ZeloWebhook} handler, and auto-fulfills the request with the handler's
 * return value as proof.
 *
 * <p>Mounted at {@code zelo.webhook-path} (default {@code /zelo/webhooks}).</p>
 */
@RestController
public class ZeloWebhookController {

    private static final Logger log = LoggerFactory.getLogger(ZeloWebhookController.class);
    private static final String SIGNATURE_HEADER = "X-Zelo-Signature";
    private static final String EVENT_HEADER = "X-Zelo-Event";

    private final PixWebhookValidator validator;
    private final ZeloWebhookDispatcher dispatcher;
    private final ZeloClient client;
    private final ObjectMapper objectMapper;

    public ZeloWebhookController(PixWebhookValidator validator, ZeloWebhookDispatcher dispatcher,
                                 ZeloClient client, ObjectMapper objectMapper) {
        this.validator = validator;
        this.dispatcher = dispatcher;
        this.client = client;
        this.objectMapper = objectMapper;
    }

    @PostMapping("${zelo.webhook-path:/zelo/webhooks}")
    public ResponseEntity<Void> receive(@RequestBody byte[] body, HttpServletRequest request) throws Exception {
        PixWebhookRequest webhook = PixWebhookRequest.builder()
                .sourceIp(request.getRemoteAddr())
                .header(SIGNATURE_HEADER, request.getHeader(SIGNATURE_HEADER))
                .body(body)
                .receivedAt(Instant.now())
                .build();
        try {
            validator.validate(webhook);
        } catch (PixWebhookValidationException e) {
            log.warn("Rejected webhook with invalid signature: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        String eventType = request.getHeader(EVENT_HEADER);
        JsonNode payload = objectMapper.readTree(body);
        ZeloDeletionRequest event = new ZeloDeletionRequest(
                text(payload, "requestId"), text(payload, "externalId"), text(payload, "deadline"));

        try {
            // The handler runs the integrator's own erasure; its return value is the proof.
            Object proof = dispatcher.dispatch(eventType, event);
            client.fulfill(event.requestId(), proof == null ? Map.of() : proof);
            return ResponseEntity.ok().build();
        } catch (NoHandlerRegisteredException e) {
            log.warn("No @ZeloWebhook handler for event '{}'; acknowledging without fulfillment", eventType);
            return ResponseEntity.ok().build();
        }
        // Any other exception (the handler's erasure failing) propagates → 5xx → Zelo retries.
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
