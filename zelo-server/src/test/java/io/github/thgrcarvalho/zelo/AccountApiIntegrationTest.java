package io.github.thgrcarvalho.zelo;

import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The self-service account surface end to end: signup is approval-gated, an
 * operator works the queue, an approved account self-issues a key that
 * authenticates on {@code /v1}, and one account can never see or touch another's
 * keys. The operator is seeded from {@code zelo.auth.operator-*}.
 */
@IntegrationTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "zelo.auth.session-secret=test-session-secret-please-change-in-prod",
        "zelo.auth.operator-email=ops@zelo.test",
        "zelo.auth.operator-password=operator-pass-123"
})
class AccountApiIntegrationTest extends AbstractIntegrationTest {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;
    private static final String OPERATOR_LOGIN =
            "{\"email\":\"ops@zelo.test\",\"password\":\"operator-pass-123\"}";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE audit_log, consent_events, subjects, purposes, dsr_requests, outbox_event "
                + "RESTART IDENTITY CASCADE");
        jdbc.update("DELETE FROM api_keys");
        // Keep the seeded operator (re-created only at startup); drop everyone else.
        jdbc.update("DELETE FROM accounts WHERE role <> 'OPERATOR'");
    }

    @Test
    void signupCreatesAPendingAccountThatCannotYetMintKeys() throws Exception {
        MvcResult signup = mvc.perform(post("/account/signup").contentType(JSON)
                        .content(signupBody("dev@acme.test", "Acme")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("dev@acme.test"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.role").value("USER"))
                .andReturn();
        Cookie session = sessionCookie(signup);

        // The session works for /me, reflecting PENDING...
        mvc.perform(get("/account/me").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));

        // ...but a PENDING account cannot mint a key yet.
        mvc.perform(post("/account/api-keys").cookie(session).contentType(JSON)
                        .content("{\"name\":\"too-soon\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void emailIsNormalizedAndDuplicateSignupConflicts() throws Exception {
        mvc.perform(post("/account/signup").contentType(JSON).content(signupBody("Dev@Acme.test", "Acme")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("dev@acme.test"));

        // Same email, different case → 409.
        mvc.perform(post("/account/signup").contentType(JSON).content(signupBody("dev@acme.TEST", "Acme Again")))
                .andExpect(status().isConflict());
    }

    @Test
    void badCredentialsGiveAGeneric401WithNoEnumeration() throws Exception {
        mvc.perform(post("/account/signup").contentType(JSON).content(signupBody("real@acme.test", "Acme")))
                .andExpect(status().isCreated());

        // Wrong password for a real account...
        String wrongPassword = mvc.perform(post("/account/login").contentType(JSON)
                        .content("{\"email\":\"real@acme.test\",\"password\":\"not-the-password\"}"))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        // ...and an unknown account return the exact same message.
        String unknownEmail = mvc.perform(post("/account/login").contentType(JSON)
                        .content("{\"email\":\"ghost@acme.test\",\"password\":\"whatever-123\"}"))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        assertThat(JsonPath.<String>read(wrongPassword, "$.message"))
                .isEqualTo(JsonPath.read(unknownEmail, "$.message"));
    }

    @Test
    void operatorApprovesAndTheAccountSelfIssuesAKeyThatAuthenticatesOnV1() throws Exception {
        MvcResult signup = mvc.perform(post("/account/signup").contentType(JSON)
                        .content(signupBody("vitalio@acme.test", "Vitalio")))
                .andExpect(status().isCreated()).andReturn();
        Cookie session = sessionCookie(signup);
        String accountId = JsonPath.read(signup.getResponse().getContentAsString(), "$.id");

        Cookie operator = operatorCookie();
        // The pending account shows up in the operator's queue.
        mvc.perform(get("/account/admin/pending").cookie(operator))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.email=='vitalio@acme.test')]").exists());

        mvc.perform(post("/account/admin/accounts/" + accountId + "/approve").cookie(operator))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        // Same cookie now reflects ACTIVE (role/status are read fresh each request).
        mvc.perform(get("/account/me").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        String created = mvc.perform(post("/account/api-keys").cookie(session).contentType(JSON)
                        .content("{\"name\":\"vitalio-prod\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.api_key").exists())
                .andReturn().getResponse().getContentAsString();
        String rawKey = JsonPath.read(created, "$.api_key");

        // The self-issued key authenticates on the client API immediately.
        mvc.perform(post("/v1/purposes").header(HttpHeaders.AUTHORIZATION, rawKey).contentType(JSON)
                        .content("{\"key\":\"reminders\",\"description\":\"Reminders\",\"legal_basis\":\"CONSENT\"}"))
                .andExpect(status().isCreated());

        // It is listed back to its owner, without leaking the raw key.
        mvc.perform(get("/account/api-keys").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name=='vitalio-prod')]").exists())
                .andExpect(jsonPath("$[0].api_key").doesNotExist());
    }

    @Test
    void oneAccountCannotSeeOrTouchAnothersKeys() throws Exception {
        Cookie alice = activeAccount("alice@acme.test", "Alice Inc");
        Cookie bob = activeAccount("bob@acme.test", "Bob Inc");

        String aliceKey = mvc.perform(post("/account/api-keys").cookie(alice).contentType(JSON)
                        .content("{\"name\":\"alice-key\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String aliceKeyId = JsonPath.read(aliceKey, "$.id");

        // Bob's listing does not include Alice's key.
        mvc.perform(get("/account/api-keys").cookie(bob))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name=='alice-key')]").doesNotExist());

        // Bob cannot revoke or re-point Alice's key — same 404 as a missing key.
        mvc.perform(delete("/account/api-keys/" + aliceKeyId).cookie(bob))
                .andExpect(status().isNotFound());
        mvc.perform(patch("/account/api-keys/" + aliceKeyId + "/webhook").cookie(bob).contentType(JSON)
                        .content("{\"webhook_url\":\"https://evil.test/h\",\"webhook_secret\":\"s\"}"))
                .andExpect(status().isNotFound());

        // Alice still owns it.
        mvc.perform(get("/account/api-keys").cookie(alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name=='alice-key')]").exists());
    }

    @Test
    void operatorEndpointsRejectANonOperatorAccount() throws Exception {
        Cookie user = activeAccount("user@acme.test", "Acme");

        mvc.perform(get("/account/admin/pending").cookie(user))
                .andExpect(status().isForbidden());
        mvc.perform(post("/account/admin/accounts/" + java.util.UUID.randomUUID() + "/approve").cookie(user))
                .andExpect(status().isForbidden());
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
        // The logout response expires the cookie.
        assertThat(cleared).contains("zelo_session=").contains("Max-Age=0");

        // With no session, a protected call is rejected.
        mvc.perform(get("/account/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void signupValidatesEmailAndPasswordStrength() throws Exception {
        mvc.perform(post("/account/signup").contentType(JSON)
                        .content("{\"email\":\"not-an-email\",\"password\":\"longenough\",\"org_name\":\"Acme\"}"))
                .andExpect(status().isBadRequest());
        // Too-short password.
        mvc.perform(post("/account/signup").contentType(JSON)
                        .content("{\"email\":\"short@acme.test\",\"password\":\"short\",\"org_name\":\"Acme\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void theSessionCookieIsHttpOnlyAndSecure() throws Exception {
        String setCookie = mvc.perform(post("/account/signup").contentType(JSON)
                        .content(signupBody("cookie@acme.test", "Acme")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie)
                .contains("HttpOnly")
                .contains("Secure")
                .contains("SameSite=Lax");
    }

    // --- helpers -----------------------------------------------------------------

    /** Sign up, then have the seeded operator approve — returns the active session cookie. */
    private Cookie activeAccount(String email, String org) throws Exception {
        MvcResult signup = mvc.perform(post("/account/signup").contentType(JSON).content(signupBody(email, org)))
                .andExpect(status().isCreated()).andReturn();
        Cookie session = sessionCookie(signup);
        String accountId = JsonPath.read(signup.getResponse().getContentAsString(), "$.id");
        mvc.perform(post("/account/admin/accounts/" + accountId + "/approve").cookie(operatorCookie()))
                .andExpect(status().isOk());
        return session;
    }

    private Cookie operatorCookie() throws Exception {
        MvcResult login = mvc.perform(post("/account/login").contentType(JSON).content(OPERATOR_LOGIN))
                .andExpect(status().isOk()).andReturn();
        return sessionCookie(login);
    }

    private static String signupBody(String email, String org) {
        return String.format("{\"email\":\"%s\",\"password\":\"user-pass-123\",\"org_name\":\"%s\"}", email, org);
    }

    /** Pull the {@code zelo_session} cookie out of the response's Set-Cookie header. */
    private static Cookie sessionCookie(MvcResult result) {
        String header = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(header).as("Set-Cookie header present").isNotNull();
        assertThat(header).startsWith("zelo_session=");
        String pair = header.split(";", 2)[0];
        int eq = pair.indexOf('=');
        return new Cookie(pair.substring(0, eq), pair.substring(eq + 1));
    }
}
