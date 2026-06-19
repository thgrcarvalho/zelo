package io.github.thgrcarvalho.zelo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fail-closed: with verification required (the default) and mail unconfigured,
 * signup must return 503 and persist nothing — never silently create an account
 * that can't be verified. Mail is left disabled (the logging no-op sender), so this
 * never touches SMTP.
 */
@IntegrationTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "zelo.auth.session-secret=test-session-secret-please-change-in-prod",
        "zelo.mail.enabled=false",
        "zelo.mail.require-verification=true"
})
class MailFailClosedIntegrationTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        // The container is shared across test classes; clear everything that FKs back
        // to api_keys/accounts (subjects → api_keys, etc.) before deleting them.
        jdbc.execute("TRUNCATE audit_log, consent_events, subjects, purposes, dsr_requests, outbox_event "
                + "RESTART IDENTITY CASCADE");
        jdbc.update("DELETE FROM api_keys");
        jdbc.update("DELETE FROM account_tokens");
        jdbc.update("DELETE FROM accounts");
    }

    @Test
    void signupIs503AndCreatesNoAccountWhenMailIsRequiredButUnconfigured() throws Exception {
        mvc.perform(post("/account/signup").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"nomail@acme.test\",\"password\":\"user-pass-123\",\"org_name\":\"Acme\"}"))
                .andExpect(status().isServiceUnavailable());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM accounts", Integer.class)).isZero();
    }
}
