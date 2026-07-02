package io.github.thgrcarvalho.zelo.starter;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ZeloWebhookValidatorTest {

    private static final String SECRET = "shared-secret";
    private static final byte[] BODY = "{\"requestId\":\"r1\"}".getBytes(StandardCharsets.UTF_8);

    private final ZeloWebhookValidator validator = new ZeloWebhookValidator(SECRET);

    @Test
    void acceptsAPrefixedHexSignature() throws Exception {
        assertThat(validator.isValid(BODY, "sha256=" + hmacHex(SECRET, BODY))).isTrue();
    }

    @Test
    void acceptsABareHexSignature() throws Exception {
        assertThat(validator.isValid(BODY, hmacHex(SECRET, BODY))).isTrue();
    }

    @Test
    void acceptsUppercaseHex() throws Exception {
        assertThat(validator.isValid(BODY, hmacHex(SECRET, BODY).toUpperCase())).isTrue();
    }

    @Test
    void rejectsATamperedBody() throws Exception {
        String signature = "sha256=" + hmacHex(SECRET, BODY);
        assertThat(validator.isValid("{\"requestId\":\"r2\"}".getBytes(StandardCharsets.UTF_8), signature))
                .isFalse();
    }

    @Test
    void rejectsAWrongSecret() throws Exception {
        assertThat(validator.isValid(BODY, "sha256=" + hmacHex("other-secret", BODY))).isFalse();
    }

    @Test
    void rejectsMissingOrBlankSignatures() {
        assertThat(validator.isValid(BODY, null)).isFalse();
        assertThat(validator.isValid(BODY, "")).isFalse();
        assertThat(validator.isValid(BODY, "  ")).isFalse();
        assertThat(validator.isValid(null, "sha256=abc")).isFalse();
    }

    @Test
    void rejectsGarbageSignatures() {
        assertThat(validator.isValid(BODY, "sha256=deadbeef")).isFalse();
        assertThat(validator.isValid(BODY, "not-hex-at-all")).isFalse();
    }

    @Test
    void refusesABlankSecret() {
        assertThatThrownBy(() -> new ZeloWebhookValidator(" "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ZeloWebhookValidator(null))
                .isInstanceOf(NullPointerException.class);
    }

    private static String hmacHex(String secret, byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body));
    }
}
