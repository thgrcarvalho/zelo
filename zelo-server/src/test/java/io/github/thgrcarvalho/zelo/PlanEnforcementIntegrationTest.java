package io.github.thgrcarvalho.zelo;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.jayway.jsonpath.JsonPath;
import io.github.thgrcarvalho.zelo.domain.crypto.Passwords;
import io.github.thgrcarvalho.zelo.infrastructure.scheduling.UsageAlertJob;
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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The free-tier gate end to end: hard cap at ceiling x multiplier (429, explicit,
 * never silent), key-count cap (409), PRO and bootstrap keys unmetered, and the
 * threshold alert emails sent exactly once per (month, metric, threshold).
 */
@IntegrationTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "zelo.auth.session-secret=plans-test-secret-plans-test-secret",
        "zelo.plans.free.subjects-per-month=1",
        "zelo.plans.free.audit-events-per-month=100",
        "zelo.plans.free.api-keys=2",
        "zelo.bootstrap.api-key=plans-bootstrap-key",
        "zelo.bootstrap.name=plans-bootstrap",
        "zelo.mail.enabled=true",
        "zelo.mail.from=no-reply@zelo.test",
        "zelo.mail.base-url=https://zelo.test",
        "spring.mail.host=127.0.0.1",
        "spring.mail.port=3025",
        "spring.mail.username=",
        "spring.mail.password=",
        "spring.mail.properties.mail.smtp.ssl.enable=false",
        "spring.mail.properties.mail.smtp.auth=false",
        "spring.mail.properties.mail.smtp.starttls.enable=false"
})
class PlanEnforcementIntegrationTest extends AbstractIntegrationTest {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;
    private static final String EMAIL = "plans@acme.test";
    private static final String PASSWORD = "plans-password-1";

    @RegisterExtension
    GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP);

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired UsageAlertJob alertJob;

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
                VALUES (?, ?, ?, 'Plans Test', 'ACTIVE', now())
                """, accountId, EMAIL, Passwords.hash(PASSWORD));
    }

    @Test
    void freeSubjectsHardCapBlocksAt3xCeilingAndProLifts() throws Exception {
        Cookie session = login();
        String apiKey = mintKey(session, "cap-test");

        // Ceiling 1, multiplier 3 -> writes 1..3 pass (soft zone), the 4th is refused.
        for (int i = 1; i <= 3; i++) {
            createSubject(apiKey, "u-" + i).andExpect(status().isOk());
        }
        createSubject(apiKey, "u-4").andExpect(status().isTooManyRequests());

        // PRO is unmetered: the same write goes through after an upgrade.
        jdbc.update("UPDATE accounts SET plan = 'PRO' WHERE id = ?", accountId);
        createSubject(apiKey, "u-4").andExpect(status().isOk());

        // PRO usage response carries no limits block.
        mvc.perform(get("/account/usage").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plan").value("PRO"))
                .andExpect(jsonPath("$.limits").doesNotExist());
    }

    @Test
    void consentPathIsNoSideDoorAroundTheSubjectsCap() throws Exception {
        Cookie session = login();
        String apiKey = mintKey(session, "side-door");
        mvc.perform(post("/v1/purposes").header(HttpHeaders.AUTHORIZATION, apiKey).contentType(JSON)
                        .content("{\"key\":\"mkt\",\"description\":\"marketing\",\"legal_basis\":\"CONSENT\"}"))
                .andExpect(status().isCreated());
        for (int i = 1; i <= 3; i++) {
            createSubject(apiKey, "u-" + i).andExpect(status().isOk()); // fill to the hard cap
        }

        // A consent that would mint a NEW subject faces the subjects cap too...
        mvc.perform(post("/v1/consents").header(HttpHeaders.AUTHORIZATION, apiKey).contentType(JSON)
                        .content("{\"external_id\":\"new-user\",\"purpose_key\":\"mkt\",\"action\":\"GRANT\",\"source\":\"t\"}"))
                .andExpect(status().isTooManyRequests());
        // ...an EXISTING subject's consent meters by audit volume only (cap is high here)...
        mvc.perform(post("/v1/consents").header(HttpHeaders.AUTHORIZATION, apiKey).contentType(JSON)
                        .content("{\"external_id\":\"u-1\",\"purpose_key\":\"mkt\",\"action\":\"GRANT\",\"source\":\"t\"}"))
                .andExpect(status().isOk());
        // ...and WITHDRAW is a data-subject right: never gated, even for a new subject.
        mvc.perform(post("/v1/consents").header(HttpHeaders.AUTHORIZATION, apiKey).contentType(JSON)
                        .content("{\"external_id\":\"w-1\",\"purpose_key\":\"mkt\",\"action\":\"WITHDRAW\",\"source\":\"t\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void freeKeyCountCapsAtCeilingAndRevokeFreesASlot() throws Exception {
        Cookie session = login();
        String firstId = JsonPath.read(mintKeyRaw(session, "k1"), "$.id");
        mintKeyRaw(session, "k2");

        mvc.perform(post("/account/api-keys").cookie(session).contentType(JSON)
                        .content("{\"name\":\"k3\"}"))
                .andExpect(status().isConflict());

        mvc.perform(delete("/account/api-keys/" + firstId).cookie(session))
                .andExpect(status().isNoContent());
        mvc.perform(post("/account/api-keys").cookie(session).contentType(JSON)
                        .content("{\"name\":\"k3\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void bootstrapKeyWithoutAccountIsNeverMetered() throws Exception {
        for (int i = 1; i <= 5; i++) {
            createSubject("plans-bootstrap-key", "b-" + i).andExpect(status().isOk());
        }
    }

    @Test
    void alertEmailSentOncePerThresholdAndMonth() throws Exception {
        Cookie session = login();
        String apiKey = mintKey(session, "alert-test");
        createSubject(apiKey, "u-1").andExpect(status().isOk()); // 100% of ceiling 1

        alertJob.run();
        assertThat(greenMail.getReceivedMessages()).hasSize(1);
        assertThat(greenMail.getReceivedMessages()[0].getSubject()).contains("subjects at 100%");

        // Re-running sends nothing new; both 80 and 100 are marked for the month.
        alertJob.run();
        assertThat(greenMail.getReceivedMessages()).hasSize(1);
        Integer marks = jdbc.queryForObject(
                "SELECT count(*) FROM usage_alerts WHERE account_id = ? AND metric = 'subjects'",
                Integer.class, accountId);
        assertThat(marks).isEqualTo(2);
    }

    private org.springframework.test.web.servlet.ResultActions createSubject(String apiKey, String externalId)
            throws Exception {
        return mvc.perform(post("/v1/subjects").header(HttpHeaders.AUTHORIZATION, apiKey).contentType(JSON)
                .content("{\"external_id\":\"" + externalId + "\"}"));
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
        return JsonPath.read(mintKeyRaw(session, name), "$.api_key");
    }

    private String mintKeyRaw(Cookie session, String name) throws Exception {
        return mvc.perform(post("/account/api-keys").cookie(session).contentType(JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
    }
}
