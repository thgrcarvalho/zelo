package io.github.thgrcarvalho.zelo.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.thgrcarvalho.zelo.domain.crypto.Hashes;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;

/**
 * Guards {@code /admin/**} with a single static master key from config
 * ({@code zelo.admin.master-key}), kept deliberately separate from the
 * DB-backed client API keys: a different credential, a different URL scope, and
 * no repository access. The presented bearer key is compared to the master key
 * by SHA-256 hash in constant time. Fail-closed — if no master key is
 * configured the admin API rejects every request, so a misconfigured deploy
 * never leaves runtime provisioning open.
 */
public class AdminAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AdminAuthFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";

    /** SHA-256 hex bytes of the master key, or {@code null} when unconfigured. */
    private final byte[] masterKeyHash;
    private final ObjectMapper objectMapper;

    public AdminAuthFilter(String masterKey, ObjectMapper objectMapper) {
        this.masterKeyHash = (masterKey == null || masterKey.isBlank())
                ? null
                : Hashes.sha256Hex(masterKey).getBytes(StandardCharsets.UTF_8);
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (masterKeyHash == null) {
            reject(request, response, "Admin API is not configured");
            return;
        }
        String presented = extractKey(request.getHeader(HttpHeaders.AUTHORIZATION));
        if (presented == null) {
            reject(request, response, "Missing admin key in Authorization header");
            return;
        }
        byte[] presentedHash = Hashes.sha256Hex(presented).getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(presentedHash, masterKeyHash)) {
            reject(request, response, "Invalid admin key");
            return;
        }
        chain.doFilter(request, response);
    }

    /** Log the rejected admin attempt (for forensics) and write the 401 body. */
    private void reject(HttpServletRequest request, HttpServletResponse response, String message)
            throws IOException {
        log.warn("Admin auth rejected ({}) for {} {} from {}",
                message, request.getMethod(), request.getRequestURI(), request.getRemoteAddr());
        unauthorized(response, message);
    }

    private static String extractKey(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        String value = header.trim();
        if (value.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            value = value.substring(BEARER_PREFIX.length()).trim();
        }
        return value.isEmpty() ? null : value;
    }

    private void unauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        ObjectNode body = objectMapper.createObjectNode();
        body.put("status", 401);
        body.put("error", "Unauthorized");
        body.put("message", message);
        body.put("timestamp", Instant.now().toString());
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
