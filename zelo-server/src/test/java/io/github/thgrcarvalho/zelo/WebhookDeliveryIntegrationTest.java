package io.github.thgrcarvalho.zelo;

import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpServer;
import io.github.thgrcarvalho.pixwebhook.PixWebhookRequest;
import io.github.thgrcarvalho.pixwebhook.PixWebhookValidator;
import io.github.thgrcarvalho.zelo.domain.crypto.Hashes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end signed webhook delivery: creating a DSR queues an event to the
 * outbox, the poller POSTs it to the integrator's endpoint with a valid
 * {@code X-Zelo-Signature}, and the request advances to DISPATCHED.
 */
@IntegrationTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "zelo.bootstrap.api-key=test-key",
        "zelo.bootstrap.name=test-integrator",
        "outbox.poll-interval-ms=200"
})
class WebhookDeliveryIntegrationTest extends AbstractIntegrationTest {

    private static final String KEY = "test-key";
    private static final String SECRET = "wh-secret";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    private HttpServer server;
    private final AtomicReference<byte[]> capturedBody = new AtomicReference<>();
    private final AtomicReference<String> capturedSignature = new AtomicReference<>();
    private final AtomicReference<String> capturedEvent = new AtomicReference<>();
    private final CountDownLatch received = new CountDownLatch(1);

    @BeforeEach
    void setUp() throws Exception {
        jdbc.execute("TRUNCATE audit_log, consent_events, dsr_requests, outbox_event, subjects, purposes "
                + "RESTART IDENTITY CASCADE");

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/webhook", exchange -> {
            capturedBody.set(exchange.getRequestBody().readAllBytes());
            capturedSignature.set(exchange.getRequestHeaders().getFirst("X-Zelo-Signature"));
            capturedEvent.set(exchange.getRequestHeaders().getFirst("X-Zelo-Event"));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
            received.countDown();
        });
        server.start();

        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/webhook";
        jdbc.update("UPDATE api_keys SET webhook_url = ?, webhook_secret = ? WHERE key_hash = ?",
                url, SECRET, Hashes.sha256Hex(KEY));
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void deliversASignedWebhookAndMarksTheRequestDispatched() throws Exception {
        String id = JsonPath.read(mvc.perform(post("/v1/requests").header(HttpHeaders.AUTHORIZATION, KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"external_id\":\"user-1\",\"type\":\"DELETE\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id");

        // The outbox poller delivers within a couple of poll cycles.
        assertThat(received.await(15, TimeUnit.SECONDS)).isTrue();

        byte[] body = capturedBody.get();
        String payload = new String(body);
        assertThat(payload).contains("\"requestId\":\"" + id + "\"").contains("\"externalId\":\"user-1\"");
        assertThat(capturedEvent.get()).isEqualTo("dsr.delete.requested");

        // The signature validates with pix-webhook-validator (the receiver's library).
        PixWebhookValidator validator = PixWebhookValidator.builder()
                .hmacSecret(SECRET)
                .signatureHeader("X-Zelo-Signature")
                .build();
        PixWebhookRequest webhook = PixWebhookRequest.builder()
                .sourceIp("127.0.0.1")
                .header("X-Zelo-Signature", capturedSignature.get())
                .body(body)
                .receivedAt(Instant.now())
                .build();
        assertThatCode(() -> validator.validate(webhook)).doesNotThrowAnyException();

        // After successful delivery the request advances RECEIVED → DISPATCHED.
        assertThat(awaitStatus(id, "DISPATCHED")).isEqualTo("DISPATCHED");
    }

    private String awaitStatus(String id, String expected) throws Exception {
        String status = "";
        for (int i = 0; i < 50; i++) {
            status = JsonPath.read(mvc.perform(get("/v1/requests/" + id).header(HttpHeaders.AUTHORIZATION, KEY))
                    .andReturn().getResponse().getContentAsString(), "$.status");
            if (expected.equals(status)) {
                return status;
            }
            Thread.sleep(200);
        }
        return status;
    }
}
