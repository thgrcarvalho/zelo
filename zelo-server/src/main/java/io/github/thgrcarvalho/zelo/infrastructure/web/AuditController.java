package io.github.thgrcarvalho.zelo.infrastructure.web;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.thgrcarvalho.ratelimit.RateLimit;
import io.github.thgrcarvalho.zelo.application.AuditService;
import io.github.thgrcarvalho.zelo.application.error.BadRequestException;
import io.github.thgrcarvalho.zelo.domain.audit.AuditEntry;
import io.github.thgrcarvalho.zelo.domain.audit.ChainVerification;
import io.github.thgrcarvalho.zelo.infrastructure.bootstrap.ShowcaseChain;
import io.github.thgrcarvalho.zelo.infrastructure.security.ApiKeyPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;

@RestController
@RequestMapping("/v1/audit")
public class AuditController {

    private static final int MAX_PAGE = 1000;

    private final AuditService auditService;

    public AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    /**
     * Export the caller's audit trail, paginated. Optionally bounded by occurred_at
     * (ISO-8601 {@code from}/{@code to}). Page with {@code after_id} (the last id seen)
     * and {@code limit} (default 200, capped at {@value #MAX_PAGE}).
     */
    @GetMapping
    public List<AuditEntryResponse> export(ApiKeyPrincipal principal,
                                           @RequestParam(required = false) String from,
                                           @RequestParam(required = false) String to,
                                           @RequestParam(name = "after_id", required = false) Long afterId,
                                           @RequestParam(defaultValue = "200") int limit) {
        Instant fromInstant = parseInstant(from, "from");
        Instant toInstant = parseInstant(to, "to");
        int capped = Math.min(Math.max(limit, 1), MAX_PAGE);
        return auditService.export(principal.id(), fromInstant, toInstant, afterId, capped).stream()
                .map(AuditEntryResponse::from)
                .toList();
    }

    /** Recompute the caller's chain and report integrity ("prove the proof"). */
    @GetMapping("/verify")
    public VerifyResponse verify(ApiKeyPrincipal principal) {
        ChainVerification result = auditService.verify(principal.id());
        return new VerifyResponse(
                result.ok(), result.entriesChecked(), result.firstBrokenEntryId(), result.detail());
    }

    /**
     * Public, unauthenticated proof for the landing page's live widget: recompute a
     * synthetic showcase chain and report its integrity. Exposes only the verdict +
     * count (never any payload), so it leaks nothing. This path is the single
     * anonymous exception under {@code /v1/*} (see {@code ApiKeyAuthFilter}), CORS-
     * allowed for the site origin and rate-limited per IP. The authenticated
     * {@link #verify} above stays the per-tenant endpoint integrators call with
     * their own key.
     */
    @GetMapping("/verify/demo")
    @RateLimit(requests = 60, window = "1m", keyStrategy = RateLimit.KeyStrategy.IP_AND_PATH)
    public VerifyResponse verifyDemo() {
        ChainVerification result = auditService.verify(ShowcaseChain.API_KEY_ID);
        return new VerifyResponse(
                result.ok(), result.entriesChecked(), result.firstBrokenEntryId(), result.detail());
    }

    private static Instant parseInstant(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw new BadRequestException(
                    "Invalid '" + field + "' timestamp; expected ISO-8601, e.g. 2026-01-01T00:00:00Z");
        }
    }

    public record AuditEntryResponse(
            long id, String eventType, JsonNode payload, Instant occurredAt, String prevHash, String entryHash) {

        static AuditEntryResponse from(AuditEntry e) {
            return new AuditEntryResponse(
                    e.id(), e.eventType(), e.payload(), e.occurredAt(), e.prevHash(), e.entryHash());
        }
    }

    public record VerifyResponse(boolean ok, long entriesChecked, Long firstBrokenEntryId, String detail) {
    }
}
