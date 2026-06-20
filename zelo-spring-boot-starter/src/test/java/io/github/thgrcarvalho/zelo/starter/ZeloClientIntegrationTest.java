package io.github.thgrcarvalho.zelo.starter;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@link ZeloClient} against a stub Zelo server that speaks {@code snake_case}.
 *
 * <p>The crucial guarantee here: this test app leaves Jackson on Spring Boot's
 * default {@code camelCase}, yet every call round-trips correctly — proving the
 * client's own {@code snake_case} mapper isolates the wire format from the host
 * app's configuration (exactly the situation in a camelCase integrator).</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        classes = ZeloClientIntegrationTest.TestApp.class,
        properties = "zelo.api-key=test-api-key")
class ZeloClientIntegrationTest {

    record Recorded(String method, String path, String query, String auth, String idempotencyKey, String body) {
    }

    private static HttpServer stub;
    private static final List<Recorded> received = new CopyOnWriteArrayList<>();
    // First POST /v1/requests for "flaky" 503s, the rest succeed — to exercise retry.
    private static final AtomicInteger flakyDeletionAttempts = new AtomicInteger();

    @DynamicPropertySource
    static void zeloApi(DynamicPropertyRegistry registry) throws IOException {
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/", exchange -> {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            String query = exchange.getRequestURI().getQuery();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            received.add(new Recorded(method, path, query,
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().getFirst("Idempotency-Key"), body));

            int[] status = {200};
            String json = route(method, path, query, body, status);
            byte[] out = json == null ? new byte[0] : json.getBytes(StandardCharsets.UTF_8);
            if (out.length > 0) {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status[0], out.length);
                exchange.getResponseBody().write(out);
            } else {
                exchange.sendResponseHeaders(status[0], -1);
            }
            exchange.close();
        });
        stub.start();
        registry.add("zelo.api-url", () -> "http://127.0.0.1:" + stub.getAddress().getPort());
    }

    private static String route(String method, String path, String query, String body, int[] status) {
        if (method.equals("POST") && path.equals("/v1/subjects")) {
            status[0] = 201;
            return "{\"id\":\"sub-1\",\"external_id\":\"alice\",\"created_at\":\"2026-06-13T10:00:00Z\"}";
        }
        if (method.equals("POST") && path.equals("/v1/purposes")) {
            if (body.contains("\"key\":\"existing\"")) {
                status[0] = 409;
                return "{\"status\":409,\"message\":\"already exists\"}";
            }
            status[0] = 201;
            return "{\"id\":\"pur-mkt\",\"key\":\"marketing-emails\",\"description\":\"Marketing emails\","
                    + "\"legal_basis\":\"CONSENT\",\"created_at\":\"2026-06-13T10:00:00Z\"}";
        }
        if (method.equals("GET") && path.equals("/v1/purposes")) {
            return "[{\"id\":\"pur-ex\",\"key\":\"existing\",\"description\":\"Existing\","
                    + "\"legal_basis\":\"HEALTH_PROTECTION\",\"created_at\":\"2026-06-13T10:00:00Z\"}]";
        }
        if (method.equals("POST") && path.equals("/v1/consents")) {
            boolean withdraw = body.contains("\"action\":\"WITHDRAW\"");
            String action = withdraw ? "WITHDRAW" : "GRANT";
            boolean granted = !withdraw;
            return "{\"external_id\":\"alice\",\"current\":[{\"purpose_key\":\"marketing-emails\",\"granted\":"
                    + granted + ",\"last_action\":\"" + action + "\",\"source\":\"signup\","
                    + "\"since\":\"2026-06-13T10:00:00Z\"}],\"history\":[{\"purpose_key\":\"marketing-emails\","
                    + "\"action\":\"" + action + "\",\"source\":\"signup\",\"occurred_at\":\"2026-06-13T10:00:00Z\"}]}";
        }
        if (method.equals("GET") && path.equals("/v1/consents")) {
            if (query != null && query.contains("subject=ghost")) {
                status[0] = 404;
                return "{\"status\":404,\"message\":\"no such subject\"}";
            }
            return "{\"external_id\":\"alice\",\"current\":[{\"purpose_key\":\"marketing-emails\",\"granted\":true,"
                    + "\"last_action\":\"GRANT\",\"source\":\"signup\",\"since\":\"2026-06-13T10:00:00Z\"}],"
                    + "\"history\":[]}";
        }
        if (method.equals("POST") && path.equals("/v1/requests")) {
            // One transient 5xx before success, to prove requestDeletion() retries.
            if (body.contains("\"external_id\":\"flaky\"") && flakyDeletionAttempts.getAndIncrement() == 0) {
                status[0] = 503;
                return "{\"status\":503,\"message\":\"upstream blip\"}";
            }
            status[0] = 201;
            return "{\"id\":\"req-1\",\"type\":\"DELETE\",\"status\":\"RECEIVED\"}";
        }
        if (method.equals("POST") && path.endsWith("/fulfill")) {
            return null; // 200, no body
        }
        if (method.equals("GET") && path.startsWith("/v1/requests/")) {
            return "{\"id\":\"req-1\",\"type\":\"DELETE\",\"status\":\"FULFILLED\","
                    + "\"deadline_at\":\"2026-06-28T10:00:00Z\",\"seconds_until_deadline\":1296000,"
                    + "\"created_at\":\"2026-06-13T10:00:00Z\",\"dispatched_at\":\"2026-06-13T10:00:05Z\","
                    + "\"fulfilled_at\":\"2026-06-13T10:00:06Z\",\"fulfillment_proof\":{\"deletedRows\":1}}";
        }
        if (method.equals("GET") && path.equals("/v1/audit/verify")) {
            return "{\"ok\":true,\"entries_checked\":7,\"first_broken_entry_id\":null,\"detail\":\"chain intact\"}";
        }
        if (method.equals("GET") && path.equals("/v1/audit")) {
            return "[{\"id\":1,\"event_type\":\"consent.granted\",\"payload\":{\"externalId\":\"alice\"},"
                    + "\"occurred_at\":\"2026-06-13T10:00:00Z\",\"prev_hash\":\"0000\",\"entry_hash\":\"abcd\"}]";
        }
        status[0] = 404;
        return "{\"status\":404,\"path\":\"" + path + "\"}";
    }

    @AfterAll
    static void stop() {
        if (stub != null) {
            stub.stop(0);
        }
    }

    @Autowired
    ZeloClient zelo;

    @Test
    void registersASubject() {
        ZeloSubject s = zelo.registerSubject("alice");

        assertThat(s.externalId()).isEqualTo("alice");
        assertThat(s.id()).isEqualTo("sub-1");
        assertThat(s.createdAt()).isNotNull();
        Recorded r = last("POST", "/v1/subjects");
        assertThat(r.auth()).isEqualTo("test-api-key");
        assertThat(r.body()).contains("\"external_id\":\"alice\"");
    }

    @Test
    void definesAPurpose() {
        ZeloPurpose p = zelo.definePurpose("marketing-emails", "Marketing emails", ZeloLegalBasis.CONSENT);

        assertThat(p.key()).isEqualTo("marketing-emails");
        assertThat(p.legalBasis()).isEqualTo(ZeloLegalBasis.CONSENT);
        assertThat(last("POST", "/v1/purposes").body())
                .contains("\"key\":\"marketing-emails\"")
                .contains("\"legal_basis\":\"CONSENT\"");
    }

    @Test
    void definePurposeIsIdempotentOnConflict() {
        ZeloPurpose p = zelo.definePurpose("existing", "Existing", ZeloLegalBasis.HEALTH_PROTECTION);

        // The 409 is absorbed and the existing purpose is fetched and returned.
        assertThat(p.key()).isEqualTo("existing");
        assertThat(p.legalBasis()).isEqualTo(ZeloLegalBasis.HEALTH_PROTECTION);
        assertThat(received).anyMatch(x -> x.method().equals("GET") && x.path().equals("/v1/purposes"));
    }

    @Test
    void recordsConsentBothWays() {
        ZeloConsentReport granted = zelo.grantConsent("alice", "marketing-emails", "signup");
        assertThat(granted.isGranted("marketing-emails")).isTrue();
        assertThat(granted.current().get(0).lastAction()).isEqualTo(ZeloConsentAction.GRANT);
        assertThat(last("POST", "/v1/consents").body())
                .contains("\"action\":\"GRANT\"")
                .contains("\"purpose_key\":\"marketing-emails\"")
                .contains("\"source\":\"signup\"");

        ZeloConsentReport withdrawn = zelo.withdrawConsent("alice", "marketing-emails", "settings");
        assertThat(withdrawn.isGranted("marketing-emails")).isFalse();
        assertThat(withdrawn.current().get(0).lastAction()).isEqualTo(ZeloConsentAction.WITHDRAW);
    }

    @Test
    void isGrantedIsTrueWhenGrantedAndFalseForUnknownSubject() {
        assertThat(zelo.isGranted("alice", "marketing-emails")).isTrue();
        assertThat(zelo.isGranted("ghost", "marketing-emails")).isFalse();
    }

    @Test
    void opensADeletionRequest() {
        ZeloRequest req = zelo.requestDeletion("alice");

        assertThat(req.id()).isEqualTo("req-1");
        assertThat(req.status()).isEqualTo(ZeloRequestStatus.RECEIVED);
        assertThat(last("POST", "/v1/requests").body())
                .contains("\"external_id\":\"alice\"")
                .contains("\"type\":\"DELETE\"");
    }

    @Test
    void retriesADeletionRequestOnTransientFailureWithAStableKey() {
        // The first POST /v1/requests for "flaky" 503s; the LGPD erasure trigger must not
        // drop on a transient blip — the client retries and the call still succeeds.
        ZeloRequest req = zelo.requestDeletion("flaky");

        assertThat(req.id()).isEqualTo("req-1");
        assertThat(req.status()).isEqualTo(ZeloRequestStatus.RECEIVED);

        List<Recorded> attempts = received.stream()
                .filter(r -> r.method().equals("POST") && r.path().equals("/v1/requests")
                        && r.body().contains("\"external_id\":\"flaky\""))
                .toList();
        assertThat(attempts).hasSize(2);   // the 503, then the 201
        // Both attempts carry the SAME idempotency key, so the server dedupes the replay
        // instead of opening a duplicate deletion request.
        assertThat(attempts.get(0).idempotencyKey())
                .isNotBlank()
                .isEqualTo(attempts.get(1).idempotencyKey());
    }

    @Test
    void readsRequestState() {
        ZeloRequest req = zelo.getRequest("req-1");

        assertThat(req.status()).isEqualTo(ZeloRequestStatus.FULFILLED);
        assertThat(req.isFulfilled()).isTrue();
        assertThat(req.secondsUntilDeadline()).isEqualTo(1_296_000L);
        assertThat(req.fulfillmentProof()).containsEntry("deletedRows", 1);
    }

    @Test
    void verifiesTheAuditChain() {
        ZeloAuditVerification v = zelo.verifyAudit();

        assertThat(v.ok()).isTrue();
        assertThat(v.entriesChecked()).isEqualTo(7);
        assertThat(v.firstBrokenEntryId()).isNull();
    }

    @Test
    void exportsAuditEntries() {
        List<ZeloAuditEntry> entries = zelo.exportAudit(50);

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).eventType()).isEqualTo("consent.granted");
        assertThat(entries.get(0).payload()).containsEntry("externalId", "alice");
        assertThat(last("GET", "/v1/audit").query()).contains("limit=50");
    }

    private static Recorded last(String method, String path) {
        for (int i = received.size() - 1; i >= 0; i--) {
            Recorded r = received.get(i);
            if (r.method().equals(method) && r.path().equals(path)) {
                return r;
            }
        }
        throw new AssertionError("no recorded " + method + " " + path);
    }

    // Enable auto-config (RestClient + Jackson + the Zelo starter) WITHOUT a component
    // scan: this test lives in the starter's own package, so a scan would pick up
    // ZeloWebhookController directly and bypass its conditional wiring. A real
    // integrator scans its own package, never the starter's, so this is test-only.
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class TestApp {
    }
}
