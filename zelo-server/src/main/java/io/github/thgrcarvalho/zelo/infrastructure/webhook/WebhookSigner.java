package io.github.thgrcarvalho.zelo.infrastructure.webhook;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Computes the {@code X-Zelo-Signature} header value for an outgoing webhook:
 * {@code "sha256=" + lowercase-hex HMAC-SHA256(secret, rawBody)}.
 *
 * <p>The lowercase-hex form (and the {@code sha256=} prefix, which receivers
 * strip) is exactly what {@code pix-webhook-validator} recomputes and compares
 * on the integrator side, so a Zelo signature validates against that library
 * out of the box. pix-webhook-validator exposes no public signing method, so the
 * server side is implemented here directly.</p>
 */
public final class WebhookSigner {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String PREFIX = "sha256=";

    private WebhookSigner() {
    }

    public static String sign(String secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return PREFIX + HexFormat.of().formatHex(mac.doFinal(body));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("Failed to compute webhook HMAC", e);
        }
    }

    public static String sign(String secret, String body) {
        return sign(secret, body.getBytes(StandardCharsets.UTF_8));
    }
}
