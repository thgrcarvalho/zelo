package io.github.thgrcarvalho.zelo;

import com.jayway.jsonpath.JsonPath;
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
import static org.hamcrest.Matchers.greaterThan;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The DELETE DSR lifecycle over HTTP: create (RECEIVED, deadline computed),
 * fulfill (→ FULFILLED with proof), double-fulfill rejected, and the audit chain
 * staying intact across the transitions.
 */
@IntegrationTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "zelo.bootstrap.api-key=test-key",
        "zelo.bootstrap.name=test-integrator",
        "zelo.dsr.delete-deadline-days=15"
})
class DsrEngineIntegrationTest extends AbstractIntegrationTest {

    private static final String KEY = "test-key";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE audit_log, consent_events, dsr_requests, outbox_event, subjects, purposes RESTART IDENTITY CASCADE");
    }

    @Test
    void createsAReceivedRequestWithA15DayDeadline() throws Exception {
        mvc.perform(post("/v1/requests").header(HttpHeaders.AUTHORIZATION, KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"external_id\":\"user-1\",\"type\":\"DELETE\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("RECEIVED"))
                .andExpect(jsonPath("$.type").value("DELETE"))
                // ~15 days out; assert it is comfortably more than 14 days of seconds.
                .andExpect(jsonPath("$.seconds_until_deadline").value(greaterThan(14 * 86400)));
    }

    @Test
    void fulfillsARequestAndKeepsTheChainValid() throws Exception {
        String id = createRequest("user-1");

        mvc.perform(post("/v1/requests/" + id + "/fulfill").header(HttpHeaders.AUTHORIZATION, KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"proof\":{\"deleted_rows\":3,\"completed_at\":\"2026-06-06T12:00:00Z\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FULFILLED"))
                .andExpect(jsonPath("$.fulfilled_at").exists())
                .andExpect(jsonPath("$.fulfillment_proof.deleted_rows").value(3));

        mvc.perform(get("/v1/requests/" + id).header(HttpHeaders.AUTHORIZATION, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FULFILLED"));

        // subject.registered, dsr.delete.requested, dsr.delete.fulfilled.
        mvc.perform(get("/v1/audit/verify").header(HttpHeaders.AUTHORIZATION, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.entries_checked").value(3));
    }

    @Test
    void rejectsFulfillingAnAlreadyFulfilledRequest() throws Exception {
        String id = createRequest("user-1");
        mvc.perform(post("/v1/requests/" + id + "/fulfill").header(HttpHeaders.AUTHORIZATION, KEY)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"proof\":{}}"))
                .andExpect(status().isOk());
        mvc.perform(post("/v1/requests/" + id + "/fulfill").header(HttpHeaders.AUTHORIZATION, KEY)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"proof\":{}}"))
                .andExpect(status().isConflict());
    }

    @Test
    void unknownRequestReturns404() throws Exception {
        mvc.perform(get("/v1/requests/00000000-0000-0000-0000-000000000000")
                        .header(HttpHeaders.AUTHORIZATION, KEY))
                .andExpect(status().isNotFound());
    }

    @Test
    void creatingASecondDeletionRequestReturnsTheExistingOpenOne() throws Exception {
        String first = createRequest("user-1");
        String second = createRequest("user-1");
        // Idempotent: the in-flight request is returned, not a duplicate.
        assertThat(second).isEqualTo(first);

        // And no duplicate audit entries: subject.registered + dsr.delete.requested only.
        mvc.perform(get("/v1/audit/verify").header(HttpHeaders.AUTHORIZATION, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.entries_checked").value(2));
    }

    private String createRequest(String externalId) throws Exception {
        MvcResult result = mvc.perform(post("/v1/requests").header(HttpHeaders.AUTHORIZATION, KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"external_id\":\"" + externalId + "\",\"type\":\"DELETE\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }
}
