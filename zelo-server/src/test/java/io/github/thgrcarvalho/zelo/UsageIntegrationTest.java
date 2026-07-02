package io.github.thgrcarvalho.zelo;

import com.jayway.jsonpath.JsonPath;
import io.github.thgrcarvalho.zelo.application.UsageService;
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

import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Usage metering end to end: /v1 traffic written under an account's self-issued
 * key is visible live at GET /account/usage, and the nightly rollup stores a
 * finished month idempotently.
 */
@IntegrationTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "zelo.auth.session-secret=usage-test-secret-usage-test-secret",
})
class UsageIntegrationTest extends AbstractIntegrationTest {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;
    private static final String EMAIL = "usage@acme.test";
    private static final String PASSWORD = "usage-password-1";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired UsageService usageService;

    private UUID accountId;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE usage_rollups, audit_log, consent_events, dsr_requests, outbox_event, "
                + "subjects, purposes, account_tokens RESTART IDENTITY CASCADE");
        jdbc.update("DELETE FROM api_keys WHERE account_id IS NOT NULL");
        jdbc.update("DELETE FROM accounts");

        // An already-ACTIVE account (signup+verify flows are covered elsewhere).
        accountId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO accounts (id, email, password_hash, org_name, status, email_verified_at)
                VALUES (?, ?, ?, 'Usage Test', 'ACTIVE', now())
                """, accountId, EMAIL, Passwords.hash(PASSWORD));
    }

    @Test
    void metersLiveMonthAndRollsUpFinishedMonthsIdempotently() throws Exception {
        Cookie session = login();
        String apiKey = mintKey(session);

        // Traffic under the account's key: 1 subject, 1 purpose+consent, 1 DSR.
        mvc.perform(post("/v1/subjects").header(HttpHeaders.AUTHORIZATION, apiKey).contentType(JSON)
                        .content("{\"external_id\":\"u-1\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/v1/purposes").header(HttpHeaders.AUTHORIZATION, apiKey).contentType(JSON)
                        .content("{\"key\":\"mkt\",\"description\":\"marketing\",\"legal_basis\":\"CONSENT\"}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/v1/consents").header(HttpHeaders.AUTHORIZATION, apiKey).contentType(JSON)
                        .content("{\"external_id\":\"u-1\",\"purpose_key\":\"mkt\",\"action\":\"GRANT\",\"source\":\"test\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/v1/requests").header(HttpHeaders.AUTHORIZATION, apiKey).contentType(JSON)
                        .content("{\"external_id\":\"u-1\",\"type\":\"DELETE\"}"))
                .andExpect(status().isCreated());

        // Live current month sees all of it; nothing rolled up yet.
        String body = mvc.perform(get("/account/usage").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current_month.month").value(YearMonth.now(ZoneOffset.UTC).toString()))
                .andExpect(jsonPath("$.current_month.subjects").value(1))
                .andExpect(jsonPath("$.current_month.consent_events").value(1))
                .andExpect(jsonPath("$.current_month.dsr_requests").value(1))
                .andExpect(jsonPath("$.history").isEmpty())
                .andReturn().getResponse().getContentAsString();
        int auditEvents = JsonPath.read(body, "$.current_month.audit_events");
        assertThat(auditEvents).as("consent + dsr writes are audited").isGreaterThanOrEqualTo(2);

        // Age everything to an ABSOLUTE instant mid-previous-month (relative
        // "- INTERVAL '1 month'" math could straddle a month boundary against the
        // Java-computed YearMonth below), then roll up — twice (idempotent).
        YearMonth previous = YearMonth.now(ZoneOffset.UTC).minusMonths(1);
        OffsetDateTime midPrevious = previous.atDay(15).atStartOfDay().atOffset(ZoneOffset.UTC);
        jdbc.update("UPDATE subjects SET created_at = ?", midPrevious);
        jdbc.update("UPDATE consent_events SET created_at = ?", midPrevious);
        jdbc.update("UPDATE audit_log SET occurred_at = ?", midPrevious);
        jdbc.update("UPDATE dsr_requests SET created_at = ?", midPrevious);
        usageService.rollUpMonth(previous);
        usageService.rollUpMonth(previous);

        Map<String, Object> rollup = jdbc.queryForMap(
                "SELECT count(*) OVER () AS rows, subjects, consent_events, audit_events, dsr_requests "
                        + "FROM usage_rollups WHERE account_id = ?", accountId);
        assertThat(rollup.get("rows")).isEqualTo(1L);
        assertThat(rollup.get("subjects")).isEqualTo(1L);
        assertThat(rollup.get("consent_events")).isEqualTo(1L);
        assertThat(rollup.get("dsr_requests")).isEqualTo(1L);
        assertThat((Long) rollup.get("audit_events")).isEqualTo((long) auditEvents);

        // The endpoint now serves the stored month in history and a drained current month.
        mvc.perform(get("/account/usage").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current_month.subjects").value(0))
                .andExpect(jsonPath("$.history[0].month").value(previous.toString()))
                .andExpect(jsonPath("$.history[0].subjects").value(1))
                .andExpect(jsonPath("$.history[0].consent_events").value(1));
    }

    @Test
    void usageRequiresASession() throws Exception {
        mvc.perform(get("/account/usage")).andExpect(status().isUnauthorized());
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

    private String mintKey(Cookie session) throws Exception {
        String created = mvc.perform(post("/account/api-keys").cookie(session).contentType(JSON)
                        .content("{\"name\":\"usage-test\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(created, "$.api_key");
    }
}
