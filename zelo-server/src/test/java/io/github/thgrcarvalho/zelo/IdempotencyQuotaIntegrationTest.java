package io.github.thgrcarvalho.zelo;

import com.jayway.jsonpath.JsonPath;
import io.github.thgrcarvalho.zelo.domain.crypto.Passwords;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The idempotency layer against the plan quotas: a 429 must never be memoized
 * under the caller's {@code Idempotency-Key} (the retry after the quota lifts
 * must re-execute, not replay the stale refusal), while successful writes keep
 * normal replay semantics.
 */
@IntegrationTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "zelo.auth.session-secret=idem-test-secret-idem-test-secret",
        "zelo.plans.free.subjects-per-month=1"
})
class IdempotencyQuotaIntegrationTest extends AbstractIntegrationTest {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;
    private static final String EMAIL = "idem@acme.test";
    private static final String PASSWORD = "idem-password-1";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    private UUID accountId;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE usage_alerts, usage_rollups, audit_log, consent_events, dsr_requests, "
                + "outbox_event, subjects, purposes, account_tokens RESTART IDENTITY CASCADE");
        jdbc.update("DELETE FROM api_keys WHERE account_id IS NOT NULL");
        jdbc.update("DELETE FROM accounts");

        accountId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO accounts (id, email, password_hash, org_name, status, email_verified_at)
                VALUES (?, ?, ?, 'Idem Test', 'ACTIVE', now())
                """, accountId, EMAIL, Passwords.hash(PASSWORD));
    }

    @Test
    void quotaRefusalIsNotMemoizedUnderTheIdempotencyKey() throws Exception {
        Cookie session = login();
        String apiKey = mintKey(session, "idem-cap");

        // Ceiling 1, multiplier 3 -> writes 1..3 pass, the 4th is refused.
        for (int i = 1; i <= 3; i++) {
            createSubject(apiKey, "u-" + i, null).andExpect(status().isOk());
        }
        String key = "retry-" + UUID.randomUUID();
        createSubject(apiKey, "u-4", key).andExpect(status().isTooManyRequests());

        // Quota lifted: the SAME key must re-execute the write, not replay the 429.
        jdbc.update("UPDATE accounts SET plan = 'PRO' WHERE id = ?", accountId);
        createSubject(apiKey, "u-4", key)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.external_id").value("u-4"));
    }

    @Test
    void successfulWriteStillReplaysFromTheKey() throws Exception {
        Cookie session = login();
        String apiKey = mintKey(session, "idem-replay");

        String key = "replay-" + UUID.randomUUID();
        createSubject(apiKey, "first", key).andExpect(status().isOk());

        // Same key, different body: the cached response replays, the handler does not run.
        createSubject(apiKey, "second", key)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.external_id").value("first"));
        Integer created = jdbc.queryForObject(
                "SELECT count(*) FROM subjects WHERE external_id = 'second'", Integer.class);
        assertThat(created).isZero();
    }

    private ResultActions createSubject(String apiKey, String externalId, String idempotencyKey) throws Exception {
        MockHttpServletRequestBuilder request = post("/v1/subjects")
                .header(HttpHeaders.AUTHORIZATION, apiKey).contentType(JSON)
                .content("{\"external_id\":\"" + externalId + "\"}");
        if (idempotencyKey != null) {
            request = request.header("Idempotency-Key", idempotencyKey);
        }
        return mvc.perform(request);
    }

    private Cookie login() throws Exception {
        MvcResult login = mvc.perform(post("/account/login").contentType(JSON)
                        .content("{\"email\":\"" + EMAIL + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        Cookie cookie = login.getResponse().getCookie("zelo_session");
        assertThat(cookie).isNotNull();
        return cookie;
    }

    private String mintKey(Cookie session, String name) throws Exception {
        String body = mvc.perform(post("/account/api-keys").cookie(session).contentType(JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.api_key");
    }
}
