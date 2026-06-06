package io.github.thgrcarvalho.zelo.infrastructure.web;

import io.github.thgrcarvalho.idempotency.Idempotent;
import io.github.thgrcarvalho.ratelimit.RateLimit;
import io.github.thgrcarvalho.zelo.application.SubjectService;
import io.github.thgrcarvalho.zelo.domain.subject.Subject;
import io.github.thgrcarvalho.zelo.infrastructure.security.ApiKeyPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/v1/subjects")
public class SubjectController {

    private final SubjectService subjectService;

    public SubjectController(SubjectService subjectService) {
        this.subjectService = subjectService;
    }

    @PostMapping
    @RateLimit(requests = 100, window = "1m", keyStrategy = RateLimit.KeyStrategy.IP_AND_PATH)
    @Idempotent
    public SubjectResponse upsert(ApiKeyPrincipal principal, @Valid @RequestBody UpsertSubjectRequest request) {
        Subject subject = subjectService.upsert(principal.id(), request.externalId());
        return new SubjectResponse(subject.getId(), subject.getExternalId(), subject.getCreatedAt());
    }

    public record UpsertSubjectRequest(@NotBlank String externalId) {
    }

    public record SubjectResponse(UUID id, String externalId, Instant createdAt) {
    }
}
