package io.github.thgrcarvalho.zelo.infrastructure.webhook;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

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

    // The receiving-side contract ("sha256=" + lowercase hex HMAC-SHA256 over the raw
    // body, X-Zelo-Signature header) is pinned on the starter side by
    // ZeloWebhookValidatorTest, which accepts exactly the format asserted above.

    @Test
    void aTamperedBodyProducesADifferentSignature() {
        String original = WebhookSigner.sign("shared-secret", "{\"a\":1}".getBytes(StandardCharsets.UTF_8));
        String tampered = WebhookSigner.sign("shared-secret", "{\"a\":2}".getBytes(StandardCharsets.UTF_8));
        assertThat(tampered).isNotEqualTo(original);
    }
}
