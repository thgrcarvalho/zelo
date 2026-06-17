package io.github.thgrcarvalho.zelo.infrastructure.web;

import io.github.thgrcarvalho.zelo.application.AccountService;
import io.github.thgrcarvalho.zelo.application.ApiKeyProvisioningService;
import io.github.thgrcarvalho.zelo.application.error.ForbiddenException;
import io.github.thgrcarvalho.zelo.application.error.ServiceUnavailableException;
import io.github.thgrcarvalho.zelo.domain.account.Account;
import io.github.thgrcarvalho.zelo.domain.apikey.ApiKey;
import io.github.thgrcarvalho.zelo.domain.crypto.SessionTokens;
import io.github.thgrcarvalho.zelo.infrastructure.config.ZeloProperties;
import io.github.thgrcarvalho.zelo.infrastructure.security.AccountPrincipal;
import io.github.thgrcarvalho.zelo.infrastructure.security.SessionAuthFilter;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The self-service account surface, exposed (via nginx) only on the dashboard
 * origin {@code zelocompliance.com/account/**}, never on the {@code api.} host. A
 * {@link SessionAuthFilter}-issued cookie carries the session; the
 * {@link AccountPrincipal} parameter makes a method authentication-required (the
 * resolver 401s when absent). ACTIVE/OPERATOR authorization is checked here, and
 * key operations delegate to the account-scoped {@link ApiKeyProvisioningService}
 * methods, which enforce tenant isolation server-side.
 */
@RestController
@RequestMapping("/account")
public class AccountController {

    private final AccountService accounts;
    private final ApiKeyProvisioningService provisioning;
    private final SessionTokens sessionTokens;
    private final ZeloProperties properties;

    public AccountController(AccountService accounts, ApiKeyProvisioningService provisioning,
                            SessionTokens sessionTokens, ZeloProperties properties) {
        this.accounts = accounts;
        this.provisioning = provisioning;
        this.sessionTokens = sessionTokens;
        this.properties = properties;
    }

    // --- Public: signup / login ------------------------------------------------

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public MeResponse signup(@Valid @RequestBody SignupRequest request, HttpServletResponse response) {
        requireAuthEnabled();
        Account account = accounts.signup(request.email(), request.password(), request.orgName());
        issueSession(response, account.getId());
        return MeResponse.from(account);
    }

    @PostMapping("/login")
    public MeResponse login(@Valid @RequestBody LoginRequest request, HttpServletResponse response) {
        requireAuthEnabled();
        Account account = accounts.authenticate(request.email(), request.password());
        issueSession(response, account.getId());
        return MeResponse.from(account);
    }

    // --- Session: self -----------------------------------------------------------

    /**
     * Public + idempotent: clears the cookie regardless of whether a valid session
     * is presented, so a user can always drop to a logged-out state.
     */
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletResponse response) {
        clearSession(response);
    }

    @GetMapping("/me")
    public MeResponse me(AccountPrincipal principal) {
        return MeResponse.from(accounts.require(principal.id()));
    }

    // --- Session + ACTIVE: own API keys -----------------------------------------

    @GetMapping("/api-keys")
    public List<ApiKeyResponse> listKeys(AccountPrincipal principal) {
        requireActive(principal);
        return provisioning.listForAccount(principal.id()).stream().map(ApiKeyResponse::from).toList();
    }

    @PostMapping("/api-keys")
    @ResponseStatus(HttpStatus.CREATED)
    public CreatedApiKeyResponse createKey(AccountPrincipal principal,
                                           @Valid @RequestBody CreateKeyRequest request) {
        requireActive(principal);
        // Self-issued keys carry no billing tier; an operator sets that out-of-band.
        ApiKeyProvisioningService.Minted minted = provisioning.create(
                principal.id(), request.name(), request.webhookUrl(), request.webhookSecret(), null);
        return CreatedApiKeyResponse.from(minted);
    }

    @PatchMapping("/api-keys/{id}/webhook")
    public ApiKeyResponse updateKeyWebhook(AccountPrincipal principal, @PathVariable UUID id,
                                           @Valid @RequestBody UpdateWebhookRequest request) {
        requireActive(principal);
        return ApiKeyResponse.from(
                provisioning.updateWebhookForAccount(principal.id(), id, request.webhookUrl(), request.webhookSecret()));
    }

    @DeleteMapping("/api-keys/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeKey(AccountPrincipal principal, @PathVariable UUID id) {
        requireActive(principal);
        provisioning.revokeForAccount(principal.id(), id);
    }

    // --- Session + OPERATOR: approval queue -------------------------------------

    @GetMapping("/admin/pending")
    public List<AccountSummaryResponse> pending(AccountPrincipal principal) {
        requireOperator(principal);
        return accounts.pending().stream().map(AccountSummaryResponse::from).toList();
    }

    @PostMapping("/admin/accounts/{id}/approve")
    public AccountSummaryResponse approve(AccountPrincipal principal, @PathVariable UUID id) {
        requireOperator(principal);
        return AccountSummaryResponse.from(accounts.approve(principal.id(), id));
    }

    @PostMapping("/admin/accounts/{id}/reject")
    public AccountSummaryResponse reject(AccountPrincipal principal, @PathVariable UUID id) {
        requireOperator(principal);
        return AccountSummaryResponse.from(accounts.reject(principal.id(), id));
    }

    // --- Authorization guards ----------------------------------------------------

    private static void requireActive(AccountPrincipal principal) {
        if (!principal.isActive()) {
            throw new ForbiddenException("Account is not active; an operator must approve it first");
        }
    }

    private static void requireOperator(AccountPrincipal principal) {
        if (!principal.isOperator()) {
            throw new ForbiddenException("Operator role required");
        }
    }

    /**
     * The /account surface is disabled when no session secret is configured. Guard
     * signup/login so a misconfigured deploy returns a clean 503 (and never persists
     * a half-created account) instead of an opaque 500 from minting a session.
     */
    private void requireAuthEnabled() {
        if (!sessionTokens.isConfigured()) {
            throw new ServiceUnavailableException("Account service is not enabled");
        }
    }

    // --- Session cookie ----------------------------------------------------------

    private void issueSession(HttpServletResponse response, UUID accountId) {
        Duration ttl = Duration.ofHours(properties.getAuth().getSessionTtlHours());
        String token = sessionTokens.mint(accountId, ttl);
        response.addHeader(HttpHeaders.SET_COOKIE, sessionCookie(token, ttl).toString());
    }

    private void clearSession(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, sessionCookie("", Duration.ZERO).toString());
    }

    private static ResponseCookie sessionCookie(String value, Duration maxAge) {
        return ResponseCookie.from(SessionAuthFilter.SESSION_COOKIE, value)
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .path("/")
                .maxAge(maxAge)
                .build();
    }

    // --- DTOs --------------------------------------------------------------------

    public record SignupRequest(
            @NotBlank @Email @Size(max = 320) String email,
            @NotBlank @Size(min = 8, max = 200) String password,
            @NotBlank @Size(max = 200) String orgName) {
    }

    public record LoginRequest(
            @NotBlank @Email @Size(max = 320) String email,
            @NotBlank String password) {
    }

    public record CreateKeyRequest(
            @NotBlank @Size(max = 255) String name,
            @Size(max = 2048) String webhookUrl,
            @Size(max = 255) String webhookSecret) {
    }

    /** Both required: a webhook without a signing secret would deliver unsigned callbacks. */
    public record UpdateWebhookRequest(
            @NotBlank @Size(max = 2048) String webhookUrl,
            @NotBlank @Size(max = 255) String webhookSecret) {
    }

    /** The current account, as the dashboard sees itself. Never includes the password hash. */
    public record MeResponse(UUID id, String email, String orgName, String role, String status) {

        static MeResponse from(Account a) {
            return new MeResponse(a.getId(), a.getEmail(), a.getOrgName(), a.getRole().name(), a.getStatus().name());
        }
    }

    /** Returned once, at creation — the only time the raw key is exposed. */
    public record CreatedApiKeyResponse(UUID id, String name, String tier, String apiKey, Instant createdAt) {

        static CreatedApiKeyResponse from(ApiKeyProvisioningService.Minted minted) {
            ApiKey k = minted.apiKey();
            return new CreatedApiKeyResponse(k.getId(), k.getName(), k.getTier(), minted.rawKey(), k.getCreatedAt());
        }
    }

    /** Listing view of an owned key — never exposes the key hash or webhook secret. */
    public record ApiKeyResponse(UUID id, String name, String tier, String webhookUrl,
                                 Instant createdAt, Instant revokedAt, boolean revoked) {

        static ApiKeyResponse from(ApiKey k) {
            return new ApiKeyResponse(k.getId(), k.getName(), k.getTier(), k.getWebhookUrl(),
                    k.getCreatedAt(), k.getRevokedAt(), k.isRevoked());
        }
    }

    /** Operator-facing view of an account in the approval queue. No password hash. */
    public record AccountSummaryResponse(UUID id, String email, String orgName, String role,
                                         String status, Instant createdAt) {

        static AccountSummaryResponse from(Account a) {
            return new AccountSummaryResponse(a.getId(), a.getEmail(), a.getOrgName(),
                    a.getRole().name(), a.getStatus().name(), a.getCreatedAt());
        }
    }
}
