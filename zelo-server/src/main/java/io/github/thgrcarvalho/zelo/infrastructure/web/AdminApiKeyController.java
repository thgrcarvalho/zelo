package io.github.thgrcarvalho.zelo.infrastructure.web;

import io.github.thgrcarvalho.zelo.application.ApiKeyProvisioningService;
import io.github.thgrcarvalho.zelo.domain.apikey.ApiKey;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Operator-only API-key provisioning, guarded by the admin master key
 * ({@link io.github.thgrcarvalho.zelo.infrastructure.security.AdminAuthFilter})
 * on {@code /admin/**}. Lets an operator onboard a client — mint, list, set the
 * webhook destination, and revoke — without redeploying. The raw key is returned
 * only by {@code create}, never again; listing exposes neither the key hash nor
 * the webhook secret.
 */
@RestController
@RequestMapping("/admin/api-keys")
public class AdminApiKeyController {

    private final ApiKeyProvisioningService provisioning;

    public AdminApiKeyController(ApiKeyProvisioningService provisioning) {
        this.provisioning = provisioning;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreatedApiKeyResponse create(@Valid @RequestBody CreateApiKeyRequest request) {
        ApiKeyProvisioningService.Minted minted = provisioning.create(
                request.name(), request.webhookUrl(), request.webhookSecret(), request.tier());
        return CreatedApiKeyResponse.from(minted);
    }

    @GetMapping
    public List<ApiKeyResponse> list() {
        return provisioning.list().stream().map(ApiKeyResponse::from).toList();
    }

    @PatchMapping("/{id}/webhook")
    public ApiKeyResponse updateWebhook(@PathVariable UUID id,
                                        @Valid @RequestBody UpdateWebhookRequest request) {
        return ApiKeyResponse.from(
                provisioning.updateWebhook(id, request.webhookUrl(), request.webhookSecret()));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable UUID id) {
        provisioning.revoke(id);
    }

    public record CreateApiKeyRequest(
            @NotBlank String name,
            String webhookUrl,
            String webhookSecret,
            String tier) {
    }

    /** Both required: a webhook without a signing secret would deliver unsigned callbacks. */
    public record UpdateWebhookRequest(
            @NotBlank String webhookUrl,
            @NotBlank String webhookSecret) {
    }

    /** Returned once, at creation — the only time the raw key is exposed. */
    public record CreatedApiKeyResponse(
            UUID id,
            String name,
            String tier,
            String apiKey,
            Instant createdAt) {

        static CreatedApiKeyResponse from(ApiKeyProvisioningService.Minted minted) {
            ApiKey k = minted.apiKey();
            return new CreatedApiKeyResponse(k.getId(), k.getName(), k.getTier(),
                    minted.rawKey(), k.getCreatedAt());
        }
    }

    /** Listing view — never exposes the key hash or webhook secret. */
    public record ApiKeyResponse(
            UUID id,
            String name,
            String tier,
            String webhookUrl,
            Instant createdAt,
            Instant revokedAt,
            boolean revoked) {

        static ApiKeyResponse from(ApiKey k) {
            return new ApiKeyResponse(k.getId(), k.getName(), k.getTier(), k.getWebhookUrl(),
                    k.getCreatedAt(), k.getRevokedAt(), k.isRevoked());
        }
    }
}
