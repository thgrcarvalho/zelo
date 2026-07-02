package io.github.thgrcarvalho.zelo.infrastructure.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.thgrcarvalho.ratelimit.RateLimit;
import io.github.thgrcarvalho.zelo.application.AccountService;
import io.github.thgrcarvalho.zelo.application.BillingEventService;
import io.github.thgrcarvalho.zelo.application.BillingProvider;
import io.github.thgrcarvalho.zelo.application.error.ForbiddenException;
import io.github.thgrcarvalho.zelo.application.error.ServiceUnavailableException;
import io.github.thgrcarvalho.zelo.application.error.UnauthorizedException;
import io.github.thgrcarvalho.zelo.domain.account.Account;
import io.github.thgrcarvalho.zelo.infrastructure.config.ZeloProperties;
import io.github.thgrcarvalho.zelo.infrastructure.security.AccountPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

/**
 * Billing surface under the account origin. Checkout is session-authenticated;
 * the Asaas webhook is public but fail-closed on the {@code asaas-access-token}
 * shared secret (503 while unconfigured, 401 on mismatch, constant-time compare).
 * The webhook answers 200 even for unhandled events and malformed payloads —
 * anything else triggers Asaas's retry storm; failures we care about are logged.
 * Redelivery is safe because every transition in {@link BillingEventService} is
 * idempotent. Routing note: the existing nginx {@code /account/} proxy on the
 * dashboard vhost already exposes this path publicly — no nginx change needed.
 */
@RestController
@RequestMapping("/account/billing")
public class BillingController {

    private static final Logger log = LoggerFactory.getLogger(BillingController.class);
    private static final String WEBHOOK_TOKEN_HEADER = "asaas-access-token";

    private final BillingProvider billing;
    private final BillingEventService events;
    private final AccountService accounts;
    private final ZeloProperties properties;
    private final ObjectMapper objectMapper;

    public BillingController(BillingProvider billing, BillingEventService events,
                             AccountService accounts, ZeloProperties properties,
                             ObjectMapper objectMapper) {
        this.billing = billing;
        this.events = events;
        this.accounts = accounts;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /** Starts a PRO checkout; the browser opens the returned hosted invoice URL. */
    @PostMapping("/checkout")
    @RateLimit(requests = 10, window = "1m", keyStrategy = RateLimit.KeyStrategy.IP_AND_PATH)
    public CheckoutResponse checkout(AccountPrincipal principal) {
        if (!principal.isActive()) {
            throw new ForbiddenException("Verify your email to activate your account first");
        }
        if (!billing.isEnabled()) {
            throw new ServiceUnavailableException("Billing is not enabled on this deployment");
        }
        Account account = accounts.require(principal.id());
        return new CheckoutResponse(billing.createCheckout(account));
    }

    /** Asaas webhook receiver. */
    @PostMapping("/webhook/asaas")
    @RateLimit(requests = 120, window = "1m", keyStrategy = RateLimit.KeyStrategy.IP_AND_PATH)
    public ResponseEntity<Void> webhook(@RequestBody byte[] body, HttpServletRequest request) {
        requireWebhookToken(request.getHeader(WEBHOOK_TOKEN_HEADER));

        JsonNode payload;
        try {
            payload = objectMapper.readTree(body);
        } catch (Exception e) {
            // Malformed JSON: acknowledge (200) or Asaas retries it forever.
            log.warn("Asaas webhook: unparseable payload ({} bytes) — acknowledged", body.length);
            return ResponseEntity.ok().build();
        }

        String event = text(payload, "event");
        JsonNode payment = payload.get("payment");
        JsonNode subscriptionNode = payload.get("subscription");
        String externalReference = firstNonNull(
                text(payment, "externalReference"), text(subscriptionNode, "externalReference"),
                text(payload, "externalReference"));
        String subscriptionId = firstNonNull(
                text(payment, "subscription"), text(subscriptionNode, "id"));
        String customerId = firstNonNull(text(payment, "customer"), text(subscriptionNode, "customer"));

        UUID accountId = accountIdFrom(externalReference);
        if (event == null || accountId == null) {
            log.info("Asaas webhook: event {} without a resolvable account — acknowledged", event);
            return ResponseEntity.ok().build();
        }

        switch (event) {
            case "PAYMENT_CONFIRMED", "PAYMENT_RECEIVED" ->
                    events.paymentConfirmed(accountId, customerId, subscriptionId);
            case "PAYMENT_OVERDUE" -> events.paymentOverdue(accountId);
            case "PAYMENT_REFUNDED", "PAYMENT_CHARGEBACK_REQUESTED",
                 "SUBSCRIPTION_INACTIVATED", "SUBSCRIPTION_DELETED" ->
                    events.subscriptionEnded(accountId, subscriptionId);
            default -> log.info("Asaas webhook: event {} not handled — acknowledged", event);
        }
        return ResponseEntity.ok().build();
    }

    private void requireWebhookToken(String provided) {
        String expected = properties.getBilling().getWebhookToken();
        if (expected == null || expected.isBlank()) {
            throw new ServiceUnavailableException("Billing webhook is not configured");
        }
        if (provided == null || !MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), provided.getBytes(StandardCharsets.UTF_8))) {
            throw new UnauthorizedException("Invalid webhook token");
        }
    }

    /** Parses {@code plan=PRO;account=<uuid>} — the reference authored at checkout. */
    private static UUID accountIdFrom(String externalReference) {
        if (externalReference == null) {
            return null;
        }
        for (String part : externalReference.split(";")) {
            if (part.startsWith("account=")) {
                try {
                    return UUID.fromString(part.substring("account=".length()).trim());
                } catch (IllegalArgumentException e) {
                    return null;
                }
            }
        }
        return null;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String firstNonNull(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    public record CheckoutResponse(String checkoutUrl) {
    }
}
