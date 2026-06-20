package io.github.thgrcarvalho.zelo;

import io.github.thgrcarvalho.zelo.domain.crypto.Hashes;
import java.util.UUID;
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
    void oneTenantCannotSeeAnotherTenantsData() throws Exception {
        // Tenant A (the bootstrap key) declares a purpose and records consent for "alice".
        mvc.perform(post("/v1/purposes").header(HttpHeaders.AUTHORIZATION, KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"marketing\",\"description\":\"Marketing\",\"legal_basis\":\"CONSENT\"}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/v1/consents").header(HttpHeaders.AUTHORIZATION, KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"external_id\":\"alice\",\"purpose_key\":\"marketing\",\"action\":\"GRANT\"}"))
                .andExpect(status().isOk());

        // Tenant B: a separate key (idempotent insert so the test re-runs cleanly).
        String keyB = "tenant-b-key";
        jdbc.update("INSERT INTO api_keys (id, key_hash, name, created_at) VALUES (?, ?, 'tenant-b', now()) "
                + "ON CONFLICT (key_hash) DO NOTHING", UUID.randomUUID(), Hashes.sha256Hex(keyB));

        // B sees NONE of A's data: not the subject, not the purpose, not the audit chain.
        mvc.perform(get("/v1/consents").header(HttpHeaders.AUTHORIZATION, keyB).param("subject", "alice"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/v1/purposes").header(HttpHeaders.AUTHORIZATION, keyB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/v1/audit/verify").header(HttpHeaders.AUTHORIZATION, keyB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries_checked").value(0));
    }

    @Test
    void publishesTheOpenApiSpecScopedToV1() throws Exception {
        // Generating the spec exercises the full springdoc stack (the bean only wires at
        // startup; a version incompatibility blows up here, at request time). It must be
        // reachable without an API key, carry our metadata, and expose ONLY the public /v1
        // surface — never the internal /account or /admin endpoints.
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("Zelo API"))
                .andExpect(jsonPath("$.paths['/v1/consents']").exists())
                .andExpect(jsonPath("$.paths['/account/me']").doesNotExist())
                .andExpect(jsonPath("$.paths['/account/login']").doesNotExist())
                .andExpect(jsonPath("$.paths['/admin/keys']").doesNotExist());
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
    void recordsConsentMetadataIntoTheTamperEvidentAudit() throws Exception {
        mvc.perform(post("/v1/purposes").header(HttpHeaders.AUTHORIZATION, KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"billing\",\"description\":\"Billing\",\"legal_basis\":\"CONTRACT\"}"))
                .andExpect(status().isCreated());

        mvc.perform(post("/v1/consents").header(HttpHeaders.AUTHORIZATION, KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"external_id\":\"user-1\",\"purpose_key\":\"billing\",\"action\":\"GRANT\","
                                + "\"source\":\"checkout\",\"metadata\":{\"ip\":\"203.0.113.7\"}}"))
                .andExpect(status().isOk());

        // The metadata is folded into the audited consent.granted entry (index 2:
        // purpose.created, subject.registered, consent.granted)...
        mvc.perform(get("/v1/audit").header(HttpHeaders.AUTHORIZATION, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[2].event_type").value("consent.granted"))
                .andExpect(jsonPath("$[2].payload.metadata.ip").value("203.0.113.7"));

        // ...and the chain still verifies with the metadata inside it.
        mvc.perform(get("/v1/audit/verify").header(HttpHeaders.AUTHORIZATION, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
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
