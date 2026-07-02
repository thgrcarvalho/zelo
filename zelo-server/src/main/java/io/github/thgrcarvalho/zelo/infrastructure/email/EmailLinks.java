package io.github.thgrcarvalho.zelo.infrastructure.email;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * Builds the absolute verification / reset links that go into emails — always from
 * the <b>configured</b> base URL ({@code zelo.mail.base-url}), never from the
 * request {@code Host} header (which nginx forwards verbatim and an attacker can
 * spoof to plant a phishing link). The base URL is validated at startup: https,
 * a host, and no path/query/fragment, so concatenation is deterministic.
 *
 * <p>The token rides the URL <b>fragment</b> ({@code .../app/#verify=<token>}). A
 * fragment is never sent to the server, so it stays out of nginx access logs,
 * the {@code Referer} header, and proxy logs — only the browser sees it.</p>
 */
public class EmailLinks {

    /** Canonical {@code https://host[:port]} with no trailing slash, or null when unconfigured. */
    private final String base;

    public EmailLinks(String baseUrl) {
        this.base = normalize(baseUrl);
    }

    public String verifyUrl(String rawToken) {
        return appFragment("verify", rawToken);
    }

    public String resetUrl(String rawToken) {
        return appFragment("reset", rawToken);
    }

    public String emailChangeUrl(String rawToken) {
        return appFragment("email-change", rawToken);
    }

    /** The dashboard itself — for emails that point at a screen, not a token flow. */
    public String appUrl() {
        if (base == null) {
            throw new IllegalStateException("zelo.mail.base-url is not configured");
        }
        return base + "/app/";
    }

    private String appFragment(String kind, String rawToken) {
        if (base == null) {
            throw new IllegalStateException("zelo.mail.base-url is not configured");
        }
        return base + "/app/#" + kind + "=" + rawToken;
    }

    private static String normalize(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return null;
        }
        URI uri;
        try {
            uri = new URI(baseUrl.trim());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("zelo.mail.base-url is not a valid URL: " + baseUrl, e);
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("zelo.mail.base-url must be an absolute https URL with a host: " + baseUrl);
        }
        boolean hasPath = uri.getPath() != null && !uri.getPath().isBlank() && !uri.getPath().equals("/");
        if (hasPath || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException(
                    "zelo.mail.base-url must have no path, query, or fragment: " + baseUrl);
        }
        StringBuilder sb = new StringBuilder("https://").append(uri.getHost().toLowerCase(Locale.ROOT));
        if (uri.getPort() != -1) {
            sb.append(':').append(uri.getPort());
        }
        return sb.toString();
    }
}
