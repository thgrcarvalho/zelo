package io.github.thgrcarvalho.zelo.starter;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The starter end to end: a signed webhook is verified, dispatched to a
 * {@code @ZeloWebhook} handler, and auto-fulfilled back to (a stand-in) Zelo.
 */
@SpringBootTest(classes = ZeloStarterIntegrationTest.TestApp.class, properties = {
        "zelo.webhook-secret=test-secret",
        "zelo.api-key=test-api-key"
})
@AutoConfigureMockMvc
class ZeloStarterIntegrationTest {

    private static HttpServer zeloStub;
    private static final AtomicReference<String> fulfillPath = new AtomicReference<>();
    private static final AtomicReference<String> fulfillBody = new AtomicReference<>();
    private static final AtomicReference<String> fulfillAuth = new AtomicReference<>();
    private static final CountDownLatch fulfilled = new CountDownLatch(1);

    @DynamicPropertySource
    static void zeloApiUrl(DynamicPropertyRegistry registry) throws IOException {
        zeloStub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        zeloStub.createContext("/", exchange -> {
            fulfillPath.set(exchange.getRequestURI().getPath());
            fulfillBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            fulfillAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
            fulfilled.countDown();
        });
        zeloStub.start();
        registry.add("zelo.api-url", () -> "http://127.0.0.1:" + zeloStub.getAddress().getPort());
    }

    @AfterAll
    static void stopStub() {
        if (zeloStub != null) {
            zeloStub.stop(0);
        }
    }

    @Autowired MockMvc mvc;

    @Test
    void verifiesDispatchesAndAutoFulfills() throws Exception {
        byte[] body = ("{\"event\":\"dsr.delete.requested\",\"requestId\":\"req-1\",\"externalId\":\"user-1\","
                + "\"deadline\":\"2026-06-21T00:00:00Z\",\"sentAt\":\"" + Instant.now() + "\"}")
                .getBytes(StandardCharsets.UTF_8);

        // No X-Zelo-Event header: the event type is read from the signed body.
        mvc.perform(post("/zelo/webhooks")
                        .header("X-Zelo-Signature", sign("test-secret", body))
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk());

        assertThat(fulfilled.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(TestApp.erased).containsExactly("user-1");
        assertThat(fulfillPath.get()).isEqualTo("/v1/requests/req-1/fulfill");
        assertThat(fulfillAuth.get()).isEqualTo("test-api-key");
        assertThat(fulfillBody.get()).contains("deletedRows").contains("user-1");
    }

    @Test
    void rejectsAnInvalidSignature() throws Exception {
        byte[] body = "{\"requestId\":\"r\",\"externalId\":\"u\"}".getBytes(StandardCharsets.UTF_8);
        mvc.perform(post("/zelo/webhooks")
                        .header("X-Zelo-Signature", "sha256=deadbeef")
                        .header("X-Zelo-Event", "dsr.delete.requested")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsAStaleWebhook() throws Exception {
        // Valid signature, but the signed sentAt is far outside the tolerance window.
        byte[] body = ("{\"event\":\"dsr.delete.requested\",\"requestId\":\"req-stale\",\"externalId\":\"user-1\","
                + "\"deadline\":\"2026-06-21T00:00:00Z\",\"sentAt\":\""
                + Instant.now().minusSeconds(3600) + "\"}").getBytes(StandardCharsets.UTF_8);
        mvc.perform(post("/zelo/webhooks")
                        .header("X-Zelo-Signature", sign("test-secret", body))
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    private static String sign(String secret, byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
    }

    @SpringBootApplication
    static class TestApp {
        static final List<String> erased = new CopyOnWriteArrayList<>();

        @Bean
        TestEraser testEraser() {
            return new TestEraser();
        }
    }

    static class TestEraser {
        @ZeloWebhook("dsr.delete.requested")
        public Map<String, Object> erase(ZeloDeletionRequest request) {
            TestApp.erased.add(request.externalId());
            return Map.of("deletedRows", 1, "externalId", request.externalId());
        }
    }
}
