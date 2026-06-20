package io.github.thgrcarvalho.zelo;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.jayway.jsonpath.JsonPath;
import io.github.thgrcarvalho.zelo.domain.crypto.SessionTokens;
import jakarta.mail.internet.MimeMessage;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The self-service account surface end to end: instant signup emails a verification
 * link, clicking it activates the account (no operator), an active account
 * self-issues a key that authenticates on {@code /v1}, one account can never touch
 * another's keys, signup is enumeration-safe, and a password reset invalidates live
 * sessions. Email is asserted via an in-process GreenMail SMTP server — nothing
 * leaves the JVM.
 */
@IntegrationTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "zelo.auth.session-secret=test-session-secret-please-change-in-prod",
        "zelo.auth.login-max-failures=5",
        "zelo.mail.enabled=true",
        "zelo.mail.from=no-reply@zelo.test",
        "zelo.mail.base-url=https://zelo.test",
        "zelo.mail.resend-cooldown-seconds=60",
        "spring.mail.host=127.0.0.1",
        "spring.mail.port=3025",
        "spring.mail.username=",
        "spring.mail.password=",
        "spring.mail.properties.mail.smtp.ssl.enable=false",
        "spring.mail.properties.mail.smtp.auth=false",
        "spring.mail.properties.mail.smtp.starttls.enable=false"
})
class AccountApiIntegrationTest extends AbstractIntegrationTest {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;
    private static final String SESSION_COOKIE = "zelo_session";

    @RegisterExtension
    GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP);

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired SessionTokens sessionTokens;

    /** Cumulative count of emails we've explicitly waited for, so the next await blocks for a NEW one. */
    private int awaited;

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE audit_log, consent_events, subjects, purposes, dsr_requests, outbox_event "
                + "RESTART IDENTITY CASCADE");
        jdbc.update("DELETE FROM api_keys");
        jdbc.update("DELETE FROM account_tokens");
        jdbc.update("DELETE FROM accounts");
        awaited = 0;
    }

    @Test
    void signupQueuesAVerificationEmailAndCreatesAnUnverifiedAccount() throws Exception {
        mvc.perform(post("/account/signup").contentType(JSON).content(signupBody("dev@acme.test", "Acme")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.message").exists())
                // No session and no account fields are leaked by signup.
                .andExpect(jsonPath("$.id").doesNotExist())
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));

        String token = awaitToken("dev@acme.test", "verify");
        assertThat(token).isNotBlank();

        // The account exists UNVERIFIED, and only the token HASH is stored (never the raw value).
        assertThat(jdbc.queryForObject(
                "SELECT status FROM accounts WHERE email = ?", String.class, "dev@acme.test")).isEqualTo("UNVERIFIED");
        assertThat(jdbc.queryForObject(
                "SELECT email_verified_at FROM accounts WHERE email = ?", java.sql.Timestamp.class, "dev@acme.test"))
                .isNull();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM account_tokens WHERE token_hash = ?", Integer.class, token)).isZero();
    }

    @Test
    void verifyingTheEmailedTokenActivatesTheAccountAndLogsItIn() throws Exception {
        mvc.perform(post("/account/signup").contentType(JSON).content(signupBody("vitalio@acme.test", "Vitalio")))
                .andExpect(status().isAccepted());
        String token = awaitToken("vitalio@acme.test", "verify");

        MvcResult verify = mvc.perform(post("/account/verify-email").contentType(JSON).content(tokenBody(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.email_verified").value(true))
                .andReturn();
        Cookie session = sessionCookie(verify);

        // The session works and the account can now self-issue a key...
        String created = mvc.perform(post("/account/api-keys").cookie(session).contentType(JSON)
                        .content("{\"name\":\"vitalio-prod\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.api_key").exists())
                .andReturn().getResponse().getContentAsString();
        String rawKey = JsonPath.read(created, "$.api_key");

        // ...and the key authenticates on the client API immediately.
        mvc.perform(post("/v1/purposes").header(HttpHeaders.AUTHORIZATION, rawKey).contentType(JSON)
                        .content("{\"key\":\"reminders\",\"description\":\"Reminders\",\"legal_basis\":\"CONSENT\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void anUnverifiedAccountCanLogInButCannotMintKeys() throws Exception {
        mvc.perform(post("/account/signup").contentType(JSON).content(signupBody("slow@acme.test", "Acme")))
                .andExpect(status().isAccepted());
        awaitToken("slow@acme.test", "verify");

        // Login works (valid creds) but lands in an inert UNVERIFIED state.
        MvcResult login = mvc.perform(post("/account/login").contentType(JSON)
                        .content("{\"email\":\"slow@acme.test\",\"password\":\"user-pass-123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNVERIFIED"))
                .andExpect(jsonPath("$.email_verified").value(false))
                .andReturn();
        Cookie session = sessionCookie(login);

        mvc.perform(post("/account/api-keys").cookie(session).contentType(JSON).content("{\"name\":\"too-soon\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void verifyTokenIsSingleUsePurposeBoundAndGeneric() throws Exception {
        mvc.perform(post("/account/signup").contentType(JSON).content(signupBody("once@acme.test", "Once")))
                .andExpect(status().isAccepted());
        String token = awaitToken("once@acme.test", "verify");

        mvc.perform(post("/account/verify-email").contentType(JSON).content(tokenBody(token)))
                .andExpect(status().isOk());
        // Second use of the same token → generic 400 (single-use).
        mvc.perform(post("/account/verify-email").contentType(JSON).content(tokenBody(token)))
                .andExpect(status().isBadRequest());
        // Unknown / garbage token → same generic 400 (no enumeration).
        mvc.perform(post("/account/verify-email").contentType(JSON).content(tokenBody("not-a-real-token")))
                .andExpect(status().isBadRequest());
        // A verify token is rejected by the reset-confirm endpoint (purpose binding).
        mvc.perform(post("/account/password-reset/confirm").contentType(JSON)
                        .content("{\"token\":\"" + token + "\",\"password\":\"brand-new-pass\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void duplicateSignupIsEnumerationSafeAndDoesNotLeakExistence() throws Exception {
        MvcResult first = mvc.perform(post("/account/signup").contentType(JSON).content(signupBody("dup@acme.test", "Dup")))
                .andExpect(status().isAccepted()).andReturn();
        String firstToken = awaitToken("dup@acme.test", "verify");

        // Signing up again with the same email returns the byte-identical 202 body (no
        // "already exists" leak) and, within the cooldown, sends no second email.
        MvcResult second = mvc.perform(post("/account/signup").contentType(JSON)
                        .content(signupBody("dup@acme.test", "Dup Again")))
                .andExpect(status().isAccepted()).andReturn();
        assertThat(second.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        assertThat(greenMail.waitForIncomingEmail(1000, 2)).isFalse();

        // The original verification link is untouched and still works.
        mvc.perform(post("/account/verify-email").contentType(JSON).content(tokenBody(firstToken)))
                .andExpect(status().isOk());
    }

    @Test
    void passwordResetRoundTripInvalidatesExistingSessions() throws Exception {
        Cookie session = activeAccount("reset@acme.test", "Reset Inc");
        mvc.perform(get("/account/me").cookie(session)).andExpect(status().isOk());

        mvc.perform(post("/account/password-reset/request").contentType(JSON)
                        .content("{\"email\":\"reset@acme.test\"}"))
                .andExpect(status().isNoContent());
        String token = awaitToken("reset@acme.test", "reset");

        mvc.perform(post("/account/password-reset/confirm").contentType(JSON)
                        .content("{\"token\":\"" + token + "\",\"password\":\"a-brand-new-password\"}"))
                .andExpect(status().isNoContent());

        // The old session is now dead (watermark advanced)...
        mvc.perform(get("/account/me").cookie(session)).andExpect(status().isUnauthorized());
        // ...the old password no longer works, the new one does...
        mvc.perform(post("/account/login").contentType(JSON)
                        .content("{\"email\":\"reset@acme.test\",\"password\":\"user-pass-123\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/account/login").contentType(JSON)
                        .content("{\"email\":\"reset@acme.test\",\"password\":\"a-brand-new-password\"}"))
                .andExpect(status().isOk());
        // ...and the reset token cannot be replayed.
        mvc.perform(post("/account/password-reset/confirm").contentType(JSON)
                        .content("{\"token\":\"" + token + "\",\"password\":\"yet-another-pass\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void passwordResetRequestForAnUnknownEmailIsSilentAndSendsNothing() throws Exception {
        mvc.perform(post("/account/password-reset/request").contentType(JSON)
                        .content("{\"email\":\"ghost@acme.test\"}"))
                .andExpect(status().isNoContent());
        // Same 204 as a real account, and no email is sent.
        assertThat(greenMail.waitForIncomingEmail(1000, 1)).isFalse();
    }

    @Test
    void lockoutAfterTooManyFailedLogins() throws Exception {
        mvc.perform(post("/account/signup").contentType(JSON).content(signupBody("lock@acme.test", "Acme")))
                .andExpect(status().isAccepted());

        // The configured threshold (login-max-failures=5) of wrong-password attempts → 401 each.
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/account/login").contentType(JSON)
                            .content("{\"email\":\"lock@acme.test\",\"password\":\"wrong-password\"}"))
                    .andExpect(status().isUnauthorized());
        }
        // Now locked: even the CORRECT password is refused with 429 (lock checked before verify).
        mvc.perform(post("/account/login").contentType(JSON)
                        .content("{\"email\":\"lock@acme.test\",\"password\":\"user-pass-123\"}"))
                .andExpect(status().isTooManyRequests());

        // The lock is per-account, not per-IP: a different account from the same caller still logs in.
        mvc.perform(post("/account/signup").contentType(JSON).content(signupBody("free@acme.test", "Acme")))
                .andExpect(status().isAccepted());
        mvc.perform(post("/account/login").contentType(JSON)
                        .content("{\"email\":\"free@acme.test\",\"password\":\"user-pass-123\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void passwordResetLiftsTheLockout() throws Exception {
        // signup + verify (this consumes the verification email so the reset email counts correctly).
        activeAccount("recover@acme.test", "Acme");
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/account/login").contentType(JSON)
                            .content("{\"email\":\"recover@acme.test\",\"password\":\"wrong\"}"))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/account/login").contentType(JSON)
                        .content("{\"email\":\"recover@acme.test\",\"password\":\"user-pass-123\"}"))
                .andExpect(status().isTooManyRequests());

        // A locked-out legit user recovers via password reset (which clears the lockout)...
        mvc.perform(post("/account/password-reset/request").contentType(JSON)
                        .content("{\"email\":\"recover@acme.test\"}"))
                .andExpect(status().isNoContent());
        String token = awaitToken("recover@acme.test", "reset");
        mvc.perform(post("/account/password-reset/confirm").contentType(JSON)
                        .content("{\"token\":\"" + token + "\",\"password\":\"brand-new-pass-456\"}"))
                .andExpect(status().isNoContent());
        // ...and logging in with the new password succeeds (not 429).
        mvc.perform(post("/account/login").contentType(JSON)
                        .content("{\"email\":\"recover@acme.test\",\"password\":\"brand-new-pass-456\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void deleteAccountErasesItButPreservesItsKeysAsEvidence() throws Exception {
        Cookie session = activeAccount("delete-me@acme.test", "Acme");
        mvc.perform(post("/account/api-keys").cookie(session).contentType(JSON).content("{\"name\":\"to-delete\"}"))
                .andExpect(status().isCreated());

        mvc.perform(delete("/account/me").cookie(session)).andExpect(status().isNoContent());

        // The account row (email + password hash) is erased...
        Integer remaining = jdbc.queryForObject(
                "SELECT count(*) FROM accounts WHERE email = 'delete-me@acme.test'", Integer.class);
        assertThat(remaining).isZero();
        // ...login no longer works...
        mvc.perform(post("/account/login").contentType(JSON)
                        .content("{\"email\":\"delete-me@acme.test\",\"password\":\"user-pass-123\"}"))
                .andExpect(status().isUnauthorized());
        // ...but its API key survives as audit evidence: revoked + detached from the account.
        Integer key = jdbc.queryForObject(
                "SELECT count(*) FROM api_keys WHERE name = 'to-delete' AND revoked_at IS NOT NULL AND account_id IS NULL",
                Integer.class);
        assertThat(key).isEqualTo(1);
    }

    @Test
    void resendVerificationRequiresASessionAndIsThrottledThenInvalidatesThePriorLink() throws Exception {
        mvc.perform(post("/account/verify-email/resend")).andExpect(status().isUnauthorized());

        mvc.perform(post("/account/signup").contentType(JSON).content(signupBody("resend@acme.test", "Acme")))
                .andExpect(status().isAccepted());
        String firstToken = awaitToken("resend@acme.test", "verify");
        Cookie session = sessionCookie(mvc.perform(post("/account/login").contentType(JSON)
                        .content("{\"email\":\"resend@acme.test\",\"password\":\"user-pass-123\"}"))
                .andExpect(status().isOk()).andReturn());

        // A resend right after signup is within the cooldown → 204 but no new email.
        mvc.perform(post("/account/verify-email/resend").cookie(session)).andExpect(status().isNoContent());
        assertThat(greenMail.waitForIncomingEmail(1000, 2)).isFalse();

        // Age the prior token past the cooldown, then a resend goes through...
        jdbc.update("UPDATE account_tokens SET created_at = created_at - INTERVAL '5 minutes'");
        mvc.perform(post("/account/verify-email/resend").cookie(session)).andExpect(status().isNoContent());
        String secondToken = awaitToken("resend@acme.test", "verify");

        // ...the new link works and the prior one is invalidated.
        assertThat(secondToken).isNotEqualTo(firstToken);
        mvc.perform(post("/account/verify-email").contentType(JSON).content(tokenBody(firstToken)))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/account/verify-email").contentType(JSON).content(tokenBody(secondToken)))
                .andExpect(status().isOk());
    }

    @Test
    void oneAccountCannotSeeOrTouchAnothersKeys() throws Exception {
        Cookie alice = activeAccount("alice@acme.test", "Alice Inc");
        Cookie bob = activeAccount("bob@acme.test", "Bob Inc");

        String aliceKey = mvc.perform(post("/account/api-keys").cookie(alice).contentType(JSON)
                        .content("{\"name\":\"alice-key\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String aliceKeyId = JsonPath.read(aliceKey, "$.id");

        mvc.perform(get("/account/api-keys").cookie(bob))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name=='alice-key')]").doesNotExist());

        mvc.perform(delete("/account/api-keys/" + aliceKeyId).cookie(bob)).andExpect(status().isNotFound());
        mvc.perform(patch("/account/api-keys/" + aliceKeyId + "/webhook").cookie(bob).contentType(JSON)
                        .content("{\"webhook_url\":\"https://evil.test/h\",\"webhook_secret\":\"s\"}"))
                .andExpect(status().isNotFound());

        mvc.perform(get("/account/api-keys").cookie(alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name=='alice-key')]").exists());
    }

    @Test
    void protectedEndpointsRequireASession() throws Exception {
        mvc.perform(get("/account/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/account/api-keys")).andExpect(status().isUnauthorized());
    }

    @Test
    void logoutClearsTheCookieAndProtectedCallsThenFail() throws Exception {
        Cookie session = activeAccount("logout@acme.test", "Acme");

        String cleared = mvc.perform(post("/account/logout").cookie(session))
                .andExpect(status().isNoContent())
                .andReturn().getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(cleared).contains("zelo_session=").contains("Max-Age=0");

        mvc.perform(get("/account/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void theSessionCookieIsHttpOnlyAndSecure() throws Exception {
        mvc.perform(post("/account/signup").contentType(JSON).content(signupBody("cookie@acme.test", "Acme")))
                .andExpect(status().isAccepted());
        String token = awaitToken("cookie@acme.test", "verify");
        String setCookie = mvc.perform(post("/account/verify-email").contentType(JSON).content(tokenBody(token)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).contains("HttpOnly").contains("Secure").contains("SameSite=Lax");
    }

    @Test
    void presentButInvalidOrStaleSessionCookieIsRejected() throws Exception {
        assert401WithSession("garbage-token");
        assert401WithSession("abc.def");
        assert401WithSession(                                       // signed with a different secret
                new SessionTokens("a-totally-different-secret-value").mint(UUID.randomUUID(), 0L, Duration.ofHours(1)));
        assert401WithSession(sessionTokens.mint(UUID.randomUUID(), 0L, Duration.ofSeconds(-1)));   // expired
        assert401WithSession(sessionTokens.mint(UUID.randomUUID(), 0L, Duration.ofHours(1)));      // no such account
        String valid = sessionTokens.mint(UUID.randomUUID(), 0L, Duration.ofHours(1));
        assert401WithSession((valid.charAt(0) == 'A' ? "B" : "A") + valid.substring(1));  // tampered payload

        // A correctly-signed token for a REAL active account, but with the wrong watermark → 401.
        Cookie active = activeAccount("stale@acme.test", "Stale");
        String id = JsonPath.read(mvc.perform(get("/account/me").cookie(active))
                .andReturn().getResponse().getContentAsString(), "$.id");
        assert401WithSession(sessionTokens.mint(UUID.fromString(id), 1L, Duration.ofHours(1)));
    }

    @Test
    void badCredentialsGiveAGeneric401WithNoEnumeration() throws Exception {
        mvc.perform(post("/account/signup").contentType(JSON).content(signupBody("real@acme.test", "Acme")))
                .andExpect(status().isAccepted());

        String wrongPassword = mvc.perform(post("/account/login").contentType(JSON)
                        .content("{\"email\":\"real@acme.test\",\"password\":\"not-the-password\"}"))
                .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
        String unknownEmail = mvc.perform(post("/account/login").contentType(JSON)
                        .content("{\"email\":\"ghost@acme.test\",\"password\":\"whatever-123\"}"))
                .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();

        assertThat(JsonPath.<String>read(wrongPassword, "$.message"))
                .isEqualTo(JsonPath.read(unknownEmail, "$.message"));
    }

    @Test
    void validatesBodiesAcrossEndpoints() throws Exception {
        // signup
        mvc.perform(post("/account/signup").contentType(JSON)
                        .content("{\"email\":\"not-an-email\",\"password\":\"longenough\",\"org_name\":\"Acme\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/account/signup").contentType(JSON)
                        .content("{\"email\":\"short@acme.test\",\"password\":\"short\",\"org_name\":\"Acme\"}"))
                .andExpect(status().isBadRequest());
        // login
        mvc.perform(post("/account/login").contentType(JSON).content("{\"email\":\"\",\"password\":\"\"}"))
                .andExpect(status().isBadRequest());
        // verify-email blank token
        mvc.perform(post("/account/verify-email").contentType(JSON).content("{\"token\":\"\"}"))
                .andExpect(status().isBadRequest());
        // reset-request bad email + reset-confirm short password
        mvc.perform(post("/account/password-reset/request").contentType(JSON).content("{\"email\":\"nope\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/account/password-reset/confirm").contentType(JSON)
                        .content("{\"token\":\"t\",\"password\":\"short\"}"))
                .andExpect(status().isBadRequest());
        // key body
        Cookie user = activeAccount("validate@acme.test", "Validate");
        mvc.perform(post("/account/api-keys").cookie(user).contentType(JSON).content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(patch("/account/api-keys/" + UUID.randomUUID() + "/webhook").cookie(user).contentType(JSON)
                        .content("{\"webhook_url\":\"https://app.example.com/h\",\"webhook_secret\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void selfServiceWebhookRejectsSsrfTargetsButAllowsPublicHttps() throws Exception {
        Cookie user = activeAccount("ssrf@acme.test", "SSRF Inc");

        for (String bad : new String[]{
                "http://example.com/h",
                "https://127.0.0.1/h",
                "https://10.0.0.5/h",
                "https://192.168.1.10/h",
                "https://169.254.169.254/latest/meta"}) {
            mvc.perform(post("/account/api-keys").cookie(user).contentType(JSON)
                            .content("{\"name\":\"k\",\"webhook_url\":\"" + bad + "\",\"webhook_secret\":\"whsec_x\"}"))
                    .andExpect(status().isBadRequest());
        }

        mvc.perform(post("/account/api-keys").cookie(user).contentType(JSON)
                        .content("{\"name\":\"ok\",\"webhook_url\":\"https://203.0.113.10/zelo/webhooks\","
                                + "\"webhook_secret\":\"whsec_x\"}"))
                .andExpect(status().isCreated());

        String created = mvc.perform(post("/account/api-keys").cookie(user).contentType(JSON)
                        .content("{\"name\":\"patchme\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String keyId = JsonPath.read(created, "$.id");
        mvc.perform(patch("/account/api-keys/" + keyId + "/webhook").cookie(user).contentType(JSON)
                        .content("{\"webhook_url\":\"https://10.1.2.3/h\",\"webhook_secret\":\"whsec_y\"}"))
                .andExpect(status().isBadRequest());
    }

    // --- helpers -----------------------------------------------------------------

    private void assert401WithSession(String token) throws Exception {
        mvc.perform(get("/account/me").cookie(new Cookie(SESSION_COOKIE, token)))
                .andExpect(status().isUnauthorized());
    }

    /** Sign up, read the emailed verification token, verify it — returns the active session cookie. */
    private Cookie activeAccount(String email, String org) throws Exception {
        mvc.perform(post("/account/signup").contentType(JSON).content(signupBody(email, org)))
                .andExpect(status().isAccepted());
        String token = awaitToken(email, "verify");
        MvcResult verify = mvc.perform(post("/account/verify-email").contentType(JSON).content(tokenBody(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn();
        return sessionCookie(verify);
    }

    /** Block until the (cumulatively) next email has arrived, then pull the {@code #<kind>=} token for {@code to}. */
    private String awaitToken(String to, String kind) throws Exception {
        awaited++;
        assertThat(greenMail.waitForIncomingEmail(5000, awaited))
                .as("expected email #%d", awaited).isTrue();
        String found = null;
        Pattern pattern = Pattern.compile("#" + kind + "=([A-Za-z0-9_-]+)");
        for (MimeMessage msg : greenMail.getReceivedMessages()) {
            if (!to.equalsIgnoreCase(msg.getAllRecipients()[0].toString())) {
                continue;
            }
            // getContent() decodes the transfer-encoding (quoted-printable/7bit) so the
            // URL's '=' is literal; GreenMailUtil.getBody can leave it as '=3D'.
            Matcher m = pattern.matcher(msg.getContent().toString());
            while (m.find()) {
                found = m.group(1);   // keep the last match = most recent token
            }
        }
        assertThat(found).as("%s token email to %s", kind, to).isNotNull();
        return found;
    }

    private static String tokenBody(String token) {
        return "{\"token\":\"" + token + "\"}";
    }

    private static String signupBody(String email, String org) {
        return String.format("{\"email\":\"%s\",\"password\":\"user-pass-123\",\"org_name\":\"%s\"}", email, org);
    }

    private static Cookie sessionCookie(MvcResult result) {
        String header = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(header).as("Set-Cookie header present").isNotNull();
        assertThat(header).startsWith("zelo_session=");
        String pair = header.split(";", 2)[0];
        int eq = pair.indexOf('=');
        return new Cookie(pair.substring(0, eq), pair.substring(eq + 1));
    }
}
