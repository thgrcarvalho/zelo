package io.github.thgrcarvalho.zelo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The HTTP surface end to end: API-key auth, snake_case JSON, the consent
 * write/read endpoints, and {@code /v1/audit/verify}.
 */
@IntegrationTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "zelo.bootstrap.api-key=test-key",
        "zelo.bootstrap.name=test-integrator"
})
class ConsentApiIntegrationTest extends AbstractIntegrationTest {

    private static final String KEY = "test-key";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE audit_log, consent_events, subjects, purposes RESTART IDENTITY CASCADE");
    }

    @Test
    void rejectsRequestsWithoutAnApiKey() throws Exception {
        mvc.perform(post("/v1/purposes").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"x\",\"description\":\"y\",\"legal_basis\":\"CONSENT\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsAnInvalidApiKey() throws Exception {
        mvc.perform(get("/v1/purposes").header(HttpHeaders.AUTHORIZATION, "Bearer wrong-key"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void runsTheFullConsentFlowAndVerifies() throws Exception {
        mvc.perform(post("/v1/purposes").header(HttpHeaders.AUTHORIZATION, KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"billing\",\"description\":\"Billing\",\"legal_basis\":\"CONTRACT\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.key").value("billing"))
                .andExpect(jsonPath("$.legal_basis").value("CONTRACT"));

        mvc.perform(post("/v1/consents").header(HttpHeaders.AUTHORIZATION, KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"external_id\":\"user-1\",\"purpose_key\":\"billing\","
                                + "\"action\":\"GRANT\",\"source\":\"checkout\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.external_id").value("user-1"))
                .andExpect(jsonPath("$.current[0].purpose_key").value("billing"))
                .andExpect(jsonPath("$.current[0].granted").value(true));

        mvc.perform(get("/v1/consents").header(HttpHeaders.AUTHORIZATION, KEY).param("subject", "user-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current[0].purpose_key").value("billing"))
                .andExpect(jsonPath("$.history[0].action").value("GRANT"));

        mvc.perform(get("/v1/audit/verify").header(HttpHeaders.AUTHORIZATION, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.entries_checked").value(3));

        mvc.perform(get("/v1/audit").header(HttpHeaders.AUTHORIZATION, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].event_type").value("purpose.created"))
                .andExpect(jsonPath("$[0].prev_hash").value("0".repeat(64)));
    }

    @Test
    void unknownPurposeReturns404() throws Exception {
        mvc.perform(post("/v1/consents").header(HttpHeaders.AUTHORIZATION, KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"external_id\":\"u\",\"purpose_key\":\"missing\",\"action\":\"GRANT\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void validationErrorReturns400() throws Exception {
        mvc.perform(post("/v1/purposes").header(HttpHeaders.AUTHORIZATION, KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"\",\"description\":\"\",\"legal_basis\":\"CONTRACT\"}"))
                .andExpect(status().isBadRequest());
    }
}
