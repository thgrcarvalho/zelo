package io.github.thgrcarvalho.zelo.infrastructure.web;

import io.github.thgrcarvalho.idempotency.Idempotent;
import io.github.thgrcarvalho.ratelimit.RateLimit;
import io.github.thgrcarvalho.zelo.application.DsrService;
import io.github.thgrcarvalho.zelo.domain.dsr.DsrRequest;
import io.github.thgrcarvalho.zelo.domain.dsr.DsrStatus;
import io.github.thgrcarvalho.zelo.domain.dsr.DsrType;
import io.github.thgrcarvalho.zelo.infrastructure.security.ApiKeyPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/v1/requests")
public class DsrRequestController {

    private final DsrService dsrService;

    public DsrRequestController(DsrService dsrService) {
        this.dsrService = dsrService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RateLimit(requests = 100, window = "1m", keyStrategy = RateLimit.KeyStrategy.IP_AND_PATH)
    @Idempotent
    public RequestResponse create(ApiKeyPrincipal principal, @Valid @RequestBody CreateRequest request) {
        // v1 only supports DELETE; the type field is accepted for forward compatibility.
        DsrRequest created = dsrService.createDeletionRequest(principal.id(), request.externalId());
        return RequestResponse.from(created, Instant.now());
    }

    @GetMapping("/{id}")
    public RequestResponse get(ApiKeyPrincipal principal, @PathVariable UUID id) {
        return RequestResponse.from(dsrService.get(principal.id(), id), Instant.now());
    }

    @PostMapping("/{id}/fulfill")
    @RateLimit(requests = 100, window = "1m", keyStrategy = RateLimit.KeyStrategy.IP_AND_PATH)
    @Idempotent
    public RequestResponse fulfill(ApiKeyPrincipal principal, @PathVariable UUID id,
                                   @RequestBody(required = false) FulfillRequest request) {
        Map<String, Object> proof = request == null ? null : request.proof();
        return RequestResponse.from(dsrService.fulfill(principal.id(), id, proof), Instant.now());
    }

    public record CreateRequest(@NotBlank String externalId, DsrType type) {
    }

    public record FulfillRequest(Map<String, Object> proof) {
    }

    public record RequestResponse(
            UUID id,
            DsrType type,
            DsrStatus status,
            Instant deadlineAt,
            long secondsUntilDeadline,
            Instant createdAt,
            Instant dispatchedAt,
            Instant fulfilledAt,
            Map<String, Object> fulfillmentProof) {

        static RequestResponse from(DsrRequest r, Instant now) {
            return new RequestResponse(
                    r.getId(), r.getType(), r.getStatus(), r.getDeadlineAt(),
                    Duration.between(now, r.getDeadlineAt()).getSeconds(),
                    r.getCreatedAt(), r.getDispatchedAt(), r.getFulfilledAt(), r.getFulfillmentProof());
        }
    }
}
