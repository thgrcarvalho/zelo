package io.github.thgrcarvalho.zelo.infrastructure.webhook;

import io.github.thgrcarvalho.pixwebhook.PixWebhookRequest;
import io.github.thgrcarvalho.pixwebhook.PixWebhookValidator;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class WebhookSignerTest {

    @Test
    void producesAPrefixedLowercaseHexDigest() {
        String signature = WebhookSigner.sign("secret", "body".getBytes(StandardCharsets.UTF_8));
        assertThat(signature).matches("sha256=[0-9a-f]{64}");
    }

    @Test
    void isDeterministic() {
        byte[] body = "{\"requestId\":\"abc\"}".getBytes(StandardCharsets.UTF_8);
        assertThat(WebhookSigner.sign("k", body)).isEqualTo(WebhookSigner.sign("k", body));
    }

    @Test
    void matchesAnIndependentHmacComputation() throws Exception {
        byte[] body = "payload".getBytes(StandardCharsets.UTF_8);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String expected = "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));

        assertThat(WebhookSigner.sign("secret", body)).isEqualTo(expected);
    }

    @Test
    void isAcceptedByPixWebhookValidator() {
        // Proves the server signature validates with the same library the integrator
        // starter uses on the receiving side.
        byte[] body = "{\"requestId\":\"r1\",\"externalId\":\"u1\"}".getBytes(StandardCharsets.UTF_8);
        String signature = WebhookSigner.sign("shared-secret", body);

        PixWebhookValidator validator = PixWebhookValidator.builder()
                .hmacSecret("shared-secret")
                .signatureHeader("X-Zelo-Signature")
                .build();
        PixWebhookRequest request = PixWebhookRequest.builder()
                .sourceIp("127.0.0.1")
                .header("X-Zelo-Signature", signature)
                .body(body)
                .receivedAt(Instant.now())
                .build();

        assertThatCode(() -> validator.validate(request)).doesNotThrowAnyException();
    }

    @Test
    void rejectsATamperedBody() {
        String signature = WebhookSigner.sign("shared-secret", "{\"a\":1}".getBytes(StandardCharsets.UTF_8));
        PixWebhookValidator validator = PixWebhookValidator.builder()
                .hmacSecret("shared-secret")
                .signatureHeader("X-Zelo-Signature")
                .build();
        PixWebhookRequest tampered = PixWebhookRequest.builder()
                .sourceIp("127.0.0.1")
                .header("X-Zelo-Signature", signature)
                .body("{\"a\":2}".getBytes(StandardCharsets.UTF_8))
                .receivedAt(Instant.now())
                .build();

        assertThatCode(() -> validator.validate(tampered)).hasMessageContaining("signature");
    }
}
