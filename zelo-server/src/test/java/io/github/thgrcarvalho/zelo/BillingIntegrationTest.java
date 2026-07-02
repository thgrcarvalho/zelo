package io.github.thgrcarvalho.zelo;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.thgrcarvalho.zelo.domain.crypto.Passwords;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The Asaas billing port end to end against a stateful local stub: checkout
 * creates-then-reuses provider objects and returns the hosted invoice URL; the
 * webhook drives idempotent plan transitions, scoped to the tracked subscription,
 * behind the fail-closed shared-token gate.
 */
@IntegrationTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "zelo.auth.session-secret=billing-test-secret-billing-test",
        "zelo.billing.asaas-api-key=asaas-test-key",
        "zelo.billing.webhook-token=whk-test-token",
})
class BillingIntegrationTest extends AbstractIntegrationTest {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;
    private static final String EMAIL = "billing@acme.test";
    private static final String PASSWORD = "billing-password-1";
    private static final String INVOICE_URL = "https://sandbox.asaas.com/i/inv1";

    private static HttpServer asaas;
    private static final AtomicInteger customersCreated = new AtomicInteger();
    private static final AtomicInteger subscriptionsCreated = new AtomicInteger();
    private static final AtomicReference<String> postedExternalReference = new AtomicReference<>();

    @DynamicPropertySource
    static void asaasStub(DynamicPropertyRegistry registry) throws IOException {
        asaas = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        asaas.createContext("/sandbox/customers", exchange -> {
            customersCreated.incrementAndGet();
            respond(exchange, "{\"id\":\"cus_test1\"}");
        });
        asaas.createContext("/sandbox/subscriptions", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.endsWith("/payments")) {
                respond(exchange, "{\"data\":[{\"status\":\"PENDING\",\"invoiceUrl\":\"" + INVOICE_URL + "\"}]}");
            } else if ("POST".equals(exchange.getRequestMethod())) {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                int refStart = body.indexOf("plan=PRO;account=");
                if (refStart >= 0) {
                    postedExternalReference.set(body.substring(refStart, body.indexOf('"', refStart)));
                }
                subscriptionsCreated.incrementAndGet();
                respond(exchange, "{\"id\":\"sub_test1\"}");
            } else {
                // GET-before-POST reuse guard: echo the previously created subscription.
                String ref = postedExternalReference.get();
                respond(exchange, ref == null ? "{\"data\":[]}"
                        : "{\"data\":[{\"id\":\"sub_test1\",\"status\":\"ACTIVE\",\"externalReference\":\"" + ref + "\"}]}");
            }
        });
        asaas.start();
        registry.add("zelo.billing.asaas-base-url",
                () -> "http://127.0.0.1:" + asaas.getAddress().getPort() + "/sandbox");
    }

    private static void respond(HttpExchange exchange, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @AfterAll
    static void stopStub() {
        if (asaas != null) {
            asaas.stop(0);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    private UUID accountId;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE usage_alerts, usage_rollups, audit_log, consent_events, dsr_requests, "
                + "outbox_event, subjects, purposes, account_tokens RESTART IDENTITY CASCADE");
        jdbc.update("DELETE FROM api_keys WHERE account_id IS NOT NULL");
        jdbc.update("DELETE FROM accounts");
        customersCreated.set(0);
        subscriptionsCreated.set(0);
        postedExternalReference.set(null);

        accountId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO accounts (id, email, password_hash, org_name, status, email_verified_at)
                VALUES (?, ?, ?, 'Billing Test', 'ACTIVE', now())
                """, accountId, EMAIL, Passwords.hash(PASSWORD));
    }

    @Test
    void checkoutMintsProviderObjectsOnceAndReturnsTheHostedInvoiceUrl() throws Exception {
        Cookie session = login();

        mvc.perform(post("/account/billing/checkout").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checkout_url").value(INVOICE_URL));

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT billing_customer_id, billing_subscription_id FROM accounts WHERE id = ?", accountId);
        assertThat(row.get("billing_customer_id")).isEqualTo("cus_test1");
        assertThat(row.get("billing_subscription_id")).isEqualTo("sub_test1");

        // A second click reuses both provider objects (stored customer id +
        // GET-before-POST subscription match) — nothing new is created.
        mvc.perform(post("/account/billing/checkout").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checkout_url").value(INVOICE_URL));
        assertThat(customersCreated.get()).isEqualTo(1);
        assertThat(subscriptionsCreated.get()).isEqualTo(1);
    }

    @Test
    void checkoutConflictsWhenAlreadyPro() throws Exception {
        Cookie session = login();
        jdbc.update("UPDATE accounts SET plan = 'PRO', plan_status = 'ACTIVE' WHERE id = ?", accountId);

        mvc.perform(post("/account/billing/checkout").cookie(session))
                .andExpect(status().isConflict());
    }

    @Test
    void webhookDrivesThePlanLifecycleScopedToTheTrackedSubscription() throws Exception {
        String ref = "plan=PRO;account=" + accountId;

        postWebhook("{\"event\":\"PAYMENT_CONFIRMED\",\"payment\":{\"externalReference\":\"" + ref
                + "\",\"subscription\":\"sub_test1\",\"customer\":\"cus_test1\"}}")
                .andExpect(status().isOk());
        assertThat(planOf(accountId)).containsEntry("plan", "PRO").containsEntry("plan_status", "ACTIVE");

        postWebhook("{\"event\":\"PAYMENT_OVERDUE\",\"payment\":{\"externalReference\":\"" + ref + "\"}}")
                .andExpect(status().isOk());
        assertThat(planOf(accountId)).containsEntry("plan", "PRO").containsEntry("plan_status", "OVERDUE");

        // A stale subscription's end-event is ignored...
        postWebhook("{\"event\":\"SUBSCRIPTION_DELETED\",\"subscription\":{\"id\":\"sub_stale\","
                + "\"externalReference\":\"" + ref + "\"}}")
                .andExpect(status().isOk());
        assertThat(planOf(accountId)).containsEntry("plan", "PRO");

        // ...the tracked one downgrades to FREE.
        postWebhook("{\"event\":\"SUBSCRIPTION_DELETED\",\"subscription\":{\"id\":\"sub_test1\","
                + "\"externalReference\":\"" + ref + "\"}}")
                .andExpect(status().isOk());
        assertThat(planOf(accountId)).containsEntry("plan", "FREE").containsEntry("plan_status", "CANCELED");
    }

    @Test
    void webhookIsTokenGatedAndAcknowledgesMalformedPayloads() throws Exception {
        mvc.perform(post("/account/billing/webhook/asaas").contentType(JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/account/billing/webhook/asaas").contentType(JSON)
                        .header("asaas-access-token", "wrong").content("{}"))
                .andExpect(status().isUnauthorized());
        // Malformed JSON with a valid token: 200, or Asaas retries it forever.
        mvc.perform(post("/account/billing/webhook/asaas").contentType(JSON)
                        .header("asaas-access-token", "whk-test-token").content("not-json"))
                .andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.ResultActions postWebhook(String payload) throws Exception {
        return mvc.perform(post("/account/billing/webhook/asaas").contentType(JSON)
                .header("asaas-access-token", "whk-test-token").content(payload));
    }

    private Map<String, Object> planOf(UUID id) {
        return jdbc.queryForMap("SELECT plan, plan_status FROM accounts WHERE id = ?", id);
    }

    private Cookie login() throws Exception {
        MvcResult login = mvc.perform(post("/account/login").contentType(JSON)
                        .content("{\"email\":\"" + EMAIL + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        Cookie cookie = login.getResponse().getCookie("zelo_session");
        assertThat(cookie).isNotNull();
        return cookie;
    }
}
