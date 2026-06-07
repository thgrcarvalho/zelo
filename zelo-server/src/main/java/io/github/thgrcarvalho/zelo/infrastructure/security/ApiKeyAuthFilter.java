package io.github.thgrcarvalho.zelo.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.thgrcarvalho.zelo.domain.apikey.ApiKey;
import io.github.thgrcarvalho.zelo.domain.apikey.ApiKeyRepository;
import io.github.thgrcarvalho.zelo.domain.crypto.Hashes;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.Optional;

/**
 * Authenticates {@code /v1/**} requests by a static API key in the
 * {@code Authorization} header (raw or {@code Bearer <key>}). The key is matched
 * by its SHA-256 hash; on success an {@link ApiKeyPrincipal} is attached to the
 * request, on failure a 401 JSON error is written and the chain stops.
 */
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    public static final String PRINCIPAL_ATTRIBUTE = "zelo.apiKeyPrincipal";
    private static final String BEARER_PREFIX = "Bearer ";

    private final ApiKeyRepository apiKeys;
    private final ObjectMapper objectMapper;

    public ApiKeyAuthFilter(ApiKeyRepository apiKeys, ObjectMapper objectMapper) {
        this.apiKeys = apiKeys;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String rawKey = extractKey(request.getHeader(HttpHeaders.AUTHORIZATION));
        if (rawKey == null) {
            unauthorized(response, "Missing API key in Authorization header");
            return;
        }
        Optional<ApiKey> match = apiKeys.findByKeyHash(Hashes.sha256Hex(rawKey));
        if (match.isEmpty()) {
            unauthorized(response, "Invalid API key");
            return;
        }
        ApiKey apiKey = match.get();
        request.setAttribute(PRINCIPAL_ATTRIBUTE, new ApiKeyPrincipal(apiKey.getId(), apiKey.getName()));
        chain.doFilter(request, response);
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
