package io.github.thgrcarvalho.zelo.infrastructure.billing;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Thin Asaas REST client (v3). Auth is the {@code access_token} header (not
 * Bearer). Create POSTs are deliberately NOT retried — they are not idempotent
 * on Asaas's side and a retry can double-bill. Asaas error bodies echo the
 * submitted email in {@code description}, so error details are never logged
 * here — callers see only the HTTP failure.
 */
public class AsaasClient {

    public record Subscription(String id, String status, String externalReference) {
    }

    public record Payment(String status, String invoiceUrl) {
    }

    private final RestClient rest;

    public AsaasClient(RestClient.Builder builder, String baseUrl, String apiKey) {
        this.rest = builder
                .baseUrl(baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl)
                .defaultHeader("access_token", apiKey)
                .build();
    }

    /** Creates a customer; returns the {@code cus_...} id. */
    public String createCustomer(String name, String email) {
        JsonNode response = rest.post().uri("/customers")
                .body(new CustomerRequest(name, email))
                .retrieve().body(JsonNode.class);
        return text(response, "id");
    }

    /** Creates a MONTHLY credit-card subscription; returns the {@code sub_...} id. */
    public String createSubscription(String customerId, String valueBrl, String externalReference,
                                     String description) {
        String nextDueDate = LocalDate.now(ZoneId.of("America/Sao_Paulo")).toString();
        // Asaas documents `value` as numeric — send a number, not "79.00".
        JsonNode response = rest.post().uri("/subscriptions")
                .body(new SubscriptionRequest(customerId, "CREDIT_CARD", new java.math.BigDecimal(valueBrl),
                        nextDueDate, "MONTHLY", externalReference, description))
                .retrieve().body(JsonNode.class);
        return text(response, "id");
    }

    /** The customer's existing subscriptions — the GET-before-POST reuse guard. */
    public List<Subscription> subscriptionsOf(String customerId) {
        JsonNode response = rest.get().uri("/subscriptions?customer={id}", customerId)
                .retrieve().body(JsonNode.class);
        List<Subscription> out = new ArrayList<>();
        if (response != null && response.has("data")) {
            for (JsonNode row : response.get("data")) {
                out.add(new Subscription(text(row, "id"), text(row, "status"),
                        text(row, "externalReference")));
            }
        }
        return out;
    }

    /** The subscription's payments (first invoice carries the checkout URL). */
    public List<Payment> paymentsOf(String subscriptionId) {
        JsonNode response = rest.get().uri("/subscriptions/{id}/payments", subscriptionId)
                .retrieve().body(JsonNode.class);
        List<Payment> out = new ArrayList<>();
        if (response != null && response.has("data")) {
            for (JsonNode row : response.get("data")) {
                out.add(new Payment(text(row, "status"), text(row, "invoiceUrl")));
            }
        }
        return out;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    // Asaas expects camelCase — records here bypass the app-wide snake_case mapper
    // because RestClient uses its own converter... which is the app's. So these
    // field names are chosen to survive snake_case conversion unchanged (single
    // words), except nextDueDate/billingType/externalReference — mapped explicitly.
    record CustomerRequest(String name, String email) {
    }

    record SubscriptionRequest(
            String customer,
            @com.fasterxml.jackson.annotation.JsonProperty("billingType") String billingType,
            java.math.BigDecimal value,
            @com.fasterxml.jackson.annotation.JsonProperty("nextDueDate") String nextDueDate,
            String cycle,
            @com.fasterxml.jackson.annotation.JsonProperty("externalReference") String externalReference,
            String description) {
    }
}
