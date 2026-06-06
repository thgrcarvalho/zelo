package io.github.thgrcarvalho.zelo.infrastructure.web;

import io.github.thgrcarvalho.idempotency.Idempotent;
import io.github.thgrcarvalho.ratelimit.RateLimit;
import io.github.thgrcarvalho.zelo.application.ConsentReport;
import io.github.thgrcarvalho.zelo.application.ConsentService;
import io.github.thgrcarvalho.zelo.domain.consent.ConsentAction;
import io.github.thgrcarvalho.zelo.infrastructure.security.ApiKeyPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/v1/consents")
public class ConsentController {

    private final ConsentService consentService;

    public ConsentController(ConsentService consentService) {
        this.consentService = consentService;
    }

    @PostMapping
    @RateLimit(requests = 100, window = "1m", keyStrategy = RateLimit.KeyStrategy.IP_AND_PATH)
    @Idempotent
    public ConsentReportResponse record(ApiKeyPrincipal principal, @Valid @RequestBody RecordConsentRequest request) {
        ConsentReport report = consentService.record(
                principal.id(), request.externalId(), request.purposeKey(), request.action(), request.source());
        return ConsentReportResponse.from(report);
    }

    @GetMapping
    public ConsentReportResponse get(ApiKeyPrincipal principal,
                                     @RequestParam String subject,
                                     @RequestParam(required = false) String purpose) {
        ConsentReport report = consentService.getConsents(principal.id(), subject, purpose);
        return ConsentReportResponse.from(report);
    }

    public record RecordConsentRequest(
            @NotBlank String externalId,
            @NotBlank String purposeKey,
            @NotNull ConsentAction action,
            String source) {
    }

    public record ConsentStateResponse(
            String purposeKey, boolean granted, ConsentAction lastAction, String source, Instant since) {
    }

    public record ConsentHistoryResponse(
            String purposeKey, ConsentAction action, String source, Instant occurredAt) {
    }

    public record ConsentReportResponse(
            String externalId,
            List<ConsentStateResponse> current,
            List<ConsentHistoryResponse> history) {

        static ConsentReportResponse from(ConsentReport report) {
            List<ConsentStateResponse> current = report.current().stream()
                    .map(s -> new ConsentStateResponse(
                            s.purposeKey(), s.granted(), s.lastAction(), s.source(), s.since()))
                    .toList();
            List<ConsentHistoryResponse> history = report.history().stream()
                    .map(h -> new ConsentHistoryResponse(
                            h.purposeKey(), h.action(), h.source(), h.occurredAt()))
                    .toList();
            return new ConsentReportResponse(report.externalId(), current, history);
        }
    }
}
