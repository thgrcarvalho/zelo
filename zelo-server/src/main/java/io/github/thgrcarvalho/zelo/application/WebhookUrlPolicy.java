package io.github.thgrcarvalho.zelo.application;

import io.github.thgrcarvalho.zelo.application.error.BadRequestException;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;

/**
 * Guards the webhook target an account may set on the self-service path. Because
 * {@code POST/PATCH /account/api-keys/...} lets any approved integrator choose the
 * URL Zelo will later POST to, an unvalidated value is a Server-Side Request
 * Forgery sink: it could point Zelo at its own loopback, the box's private network,
 * or the cloud instance-metadata endpoint and have the server issue requests on the
 * attacker's behalf. This rejects such targets at the service boundary.
 *
 * <p>Scope is deliberate: only the account-scoped provisioning methods call this.
 * Operator/admin (`/admin`) and config-bootstrap webhooks are trusted by design
 * (the bundled demo points at an internal Docker hostname), so they are not subject
 * to this check.</p>
 *
 * <p>This validates at store time. A determined attacker could still use DNS
 * rebinding to pass validation and resolve to a private address at send time; that
 * residual risk is accepted for now (single-tenant box, operator-gated accounts) and
 * noted as a follow-up rather than blocked at send time, which would break trusted
 * internal targets.</p>
 */
public final class WebhookUrlPolicy {

    private WebhookUrlPolicy() {
    }

    /**
     * Throws {@link BadRequestException} if {@code url} is non-blank and is not a
     * deliverable, public {@code https} target. A blank/null URL is allowed (the
     * webhook is simply unset).
     */
    public static void requireDeliverable(String url) {
        if (url == null || url.isBlank()) {
            return;
        }
        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (URISyntaxException e) {
            throw new BadRequestException("webhook_url is not a valid URL");
        }
        String scheme = uri.getScheme();
        if (scheme == null || !scheme.equalsIgnoreCase("https")) {
            throw new BadRequestException("webhook_url must be an https:// URL");
        }
        if (uri.getUserInfo() != null) {
            throw new BadRequestException("webhook_url must not contain user info");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new BadRequestException("webhook_url must include a host");
        }
        InetAddress[] resolved;
        try {
            resolved = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new BadRequestException("webhook_url host does not resolve");
        }
        for (InetAddress address : resolved) {
            if (isNonPublic(address)) {
                throw new BadRequestException("webhook_url must target a public host");
            }
        }
    }

    private static boolean isNonPublic(InetAddress address) {
        return address.isLoopbackAddress()       // 127.0.0.0/8, ::1
                || address.isAnyLocalAddress()    // 0.0.0.0, ::
                || address.isLinkLocalAddress()   // 169.254.0.0/16 (incl. metadata), fe80::/10
                || address.isSiteLocalAddress()   // 10/8, 172.16/12, 192.168/16
                || address.isMulticastAddress()
                || isUniqueLocalIpv6(address);    // fc00::/7 (not covered by isSiteLocalAddress)
    }

    private static boolean isUniqueLocalIpv6(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC;
    }
}
