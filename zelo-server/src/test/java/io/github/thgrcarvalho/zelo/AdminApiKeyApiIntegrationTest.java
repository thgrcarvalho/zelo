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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The runtime provisioning surface end to end: the admin master key guards
 * {@code /admin/api-keys}, a minted key authenticates against {@code /v1/**},
 * revocation immediately blocks it, and listings never leak secrets.
 */
@IntegrationTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "zelo.bootstrap.api-key=test-key",
        "zelo.bootstrap.name=test-integrator",
        "zelo.admin.master-key=test-admin-key"
})
class AdminApiKeyApiIntegrationTest extends AbstractIntegrationTest {

    private static final String ADMIN = "test-admin-key";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE audit_log, consent_events, subjects, purposes, dsr_requests, outbox_event "
                + "RESTART IDENTITY CASCADE");
        jdbc.update("DELETE FROM api_keys WHERE name <> 'test-integrator'");
    }

    @Test
    void mintsAKeyThatAuthenticatesAgainstTheClientApi() throws Exception {
        String response = mvc.perform(post("/admin/api-keys").header(HttpHeaders.AUTHORIZATION, ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"client-acme\",\"tier\":\"internal\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.api_key").exists())
                .andExpect(jsonPath("$.name").value("client-acme"))
                .andExpect(jsonPath("$.tier").value("internal"))
                .andReturn().getResponse().getContentAsString();
        String rawKey = JsonPath.read(response, "$.api_key");

        // The freshly minted key works immediately on the client API.
        mvc.perform(post("/v1/purposes").header(HttpHeaders.AUTHORIZATION, rawKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"reminders\",\"description\":\"Reminders\",\"legal_basis\":\"CONSENT\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void revokedKeyStopsAuthenticating() throws Exception {
        String response = mvc.perform(post("/admin/api-keys").header(HttpHeaders.AUTHORIZATION, ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"client-temp\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String rawKey = JsonPath.read(response, "$.api_key");
        String id = JsonPath.read(response, "$.id");

        // Works before revocation...
        mvc.perform(post("/v1/purposes").header(HttpHeaders.AUTHORIZATION, rawKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"p1\",\"description\":\"d\",\"legal_basis\":\"CONSENT\"}"))
                .andExpect(status().isCreated());

        mvc.perform(delete("/admin/api-keys/" + id).header(HttpHeaders.AUTHORIZATION, ADMIN))
                .andExpect(status().isNoContent());

        // ...and is rejected the moment it is revoked.
        mvc.perform(post("/v1/purposes").header(HttpHeaders.AUTHORIZATION, rawKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"p2\",\"description\":\"d\",\"legal_basis\":\"CONSENT\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listingOmitsSecretsAndHashes() throws Exception {
        mvc.perform(post("/admin/api-keys").header(HttpHeaders.AUTHORIZATION, ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"client-list\",\"webhook_secret\":\"super-secret-value\"}"))
                .andExpect(status().isCreated());

        mvc.perform(get("/admin/api-keys").header(HttpHeaders.AUTHORIZATION, ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name=='client-list')]").exists())
                .andExpect(content().string(not(containsString("super-secret-value"))))
                .andExpect(content().string(not(containsString("key_hash"))));
    }

    @Test
    void acceptsBearerPrefixedAdminKey() throws Exception {
        mvc.perform(post("/admin/api-keys").header(HttpHeaders.AUTHORIZATION, "Bearer " + ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"client-bearer\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void revokeIsIdempotentAndKeyStillListsAsRevoked() throws Exception {
        String response = mvc.perform(post("/admin/api-keys").header(HttpHeaders.AUTHORIZATION, ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"client-rev\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(response, "$.id");

        mvc.perform(delete("/admin/api-keys/" + id).header(HttpHeaders.AUTHORIZATION, ADMIN))
                .andExpect(status().isNoContent());
        // A second revoke is a no-op but still succeeds (idempotent).
        mvc.perform(delete("/admin/api-keys/" + id).header(HttpHeaders.AUTHORIZATION, ADMIN))
                .andExpect(status().isNoContent());

        // The revoked key is still listed, flagged revoked — it is soft-deleted, not removed.
        mvc.perform(get("/admin/api-keys").header(HttpHeaders.AUTHORIZATION, ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name=='client-rev')].revoked").value(hasItem(true)));
    }

    @Test
    void rejectsMissingOrWrongAdminKey() throws Exception {
        mvc.perform(post("/admin/api-keys").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\"}"))
                .andExpect(status().isUnauthorized());

        mvc.perform(post("/admin/api-keys").header(HttpHeaders.AUTHORIZATION, "wrong-admin-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsBlankName() throws Exception {
        mvc.perform(post("/admin/api-keys").header(HttpHeaders.AUTHORIZATION, ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void revokingAnUnknownKeyReturns404() throws Exception {
        mvc.perform(delete("/admin/api-keys/" + java.util.UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, ADMIN))
                .andExpect(status().isNotFound());
    }
}
