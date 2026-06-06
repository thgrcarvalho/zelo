package io.github.thgrcarvalho.zelo.infrastructure.web;

import io.github.thgrcarvalho.idempotency.Idempotent;
import io.github.thgrcarvalho.ratelimit.RateLimit;
import io.github.thgrcarvalho.zelo.application.PurposeService;
import io.github.thgrcarvalho.zelo.domain.subject.LegalBasis;
import io.github.thgrcarvalho.zelo.domain.subject.Purpose;
import io.github.thgrcarvalho.zelo.infrastructure.security.ApiKeyPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/purposes")
public class PurposeController {

    private final PurposeService purposeService;

    public PurposeController(PurposeService purposeService) {
        this.purposeService = purposeService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RateLimit(requests = 100, window = "1m", keyStrategy = RateLimit.KeyStrategy.IP_AND_PATH)
    @Idempotent
    public PurposeResponse create(ApiKeyPrincipal principal, @Valid @RequestBody CreatePurposeRequest request) {
        Purpose purpose = purposeService.create(
                principal.id(), request.key(), request.description(), request.legalBasis());
        return PurposeResponse.from(purpose);
    }

    @GetMapping
    public List<PurposeResponse> list(ApiKeyPrincipal principal) {
        return purposeService.list(principal.id()).stream().map(PurposeResponse::from).toList();
    }

    public record CreatePurposeRequest(
            @NotBlank String key,
            @NotBlank String description,
            @NotNull LegalBasis legalBasis) {
    }

    public record PurposeResponse(
            UUID id, String key, String description, LegalBasis legalBasis, Instant createdAt) {

        static PurposeResponse from(Purpose p) {
            return new PurposeResponse(p.getId(), p.getKey(), p.getDescription(),
                    p.getLegalBasis(), p.getCreatedAt());
        }
    }
}
