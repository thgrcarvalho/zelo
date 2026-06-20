package io.github.thgrcarvalho.zelo;

import io.github.thgrcarvalho.zelo.infrastructure.bootstrap.ShowcaseChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The public, unauthenticated demo proof endpoint behind the landing page's live
 * widget: it verifies the synthetic showcase chain with no API key (and answers
 * the site origin with CORS), while the per-tenant {@code /v1/audit/verify} stays
 * guarded.
 */
@IntegrationTest
@AutoConfigureMockMvc
class PublicAuditVerifyIntegrationTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ShowcaseChain showcase;

    @BeforeEach
    void seedShowcase() {
        // Sibling integration tests truncate audit_log; re-seed so the demo chain is present.
        showcase.ensure();
    }

    @Test
    void verifiesTheShowcaseChainWithoutAnyApiKey() throws Exception {
        mvc.perform(get("/v1/audit/verify/demo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.entries_checked").value(8))
                .andExpect(jsonPath("$.first_broken_entry_id").doesNotExist());
    }

    @Test
    void answersTheLandingOriginWithCors() throws Exception {
        mvc.perform(get("/v1/audit/verify/demo").header(HttpHeaders.ORIGIN, "https://zelocompliance.com"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://zelocompliance.com"));
    }

    @Test
    void theAuthenticatedVerifyStillRequiresAKey() throws Exception {
        mvc.perform(get("/v1/audit/verify"))
                .andExpect(status().isUnauthorized());
    }
}
