package io.github.thgrcarvalho.zelo;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetupTest;
import io.github.thgrcarvalho.zelo.domain.crypto.Passwords;
import jakarta.mail.internet.MimeMessage;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Self-service email change end to end: password re-auth, confirmation delivered
 * to the NEW address (nothing changes until redeemed), single-use token, uniform
 * 202 for a taken address (enumeration-safe), and the heads-up to the OLD address.
 */
@IntegrationTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "zelo.auth.session-secret=email-change-secret-email-change",
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
class EmailChangeIntegrationTest extends AbstractIntegrationTest {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;
    private static final String OLD_EMAIL = "old@acme.test";
    private static final String NEW_EMAIL = "new@acme.test";
    private static final String PASSWORD = "change-password-1";
    private static final Pattern LINK = Pattern.compile("#email-change=([A-Za-z0-9_\\-\\.]+)");

    @RegisterExtension
    GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP);

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    private UUID accountId;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE account_tokens RESTART IDENTITY CASCADE");
        jdbc.update("DELETE FROM api_keys WHERE account_id IS NOT NULL");
        jdbc.update("DELETE FROM accounts");
        accountId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO accounts (id, email, password_hash, org_name, status, email_verified_at)
                VALUES (?, ?, ?, 'Change Test', 'ACTIVE', now())
                """, accountId, OLD_EMAIL, Passwords.hash(PASSWORD));
    }

    @Test
    void changesEmailEndToEndAndNotifiesTheOldAddress() throws Exception {
        Cookie session = login(OLD_EMAIL);

        mvc.perform(post("/account/email-change/request").cookie(session).contentType(JSON)
                        .content("{\"new_email\":\"" + NEW_EMAIL + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isAccepted());

        // The confirmation went to the NEW address; nothing has changed yet.
        MimeMessage confirm = awaitMessages(1)[0];
        assertThat(confirm.getAllRecipients()[0].toString()).isEqualTo(NEW_EMAIL);
        assertThat(email(accountId)).isEqualTo(OLD_EMAIL);

        Matcher m = LINK.matcher(GreenMailUtil.getBody(confirm));
        assertThat(m.find()).as("confirmation link in email body").isTrue();
        String token = m.group(1);

        mvc.perform(post("/account/email-change/confirm").contentType(JSON)
                        .content("{\"token\":\"" + token + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(NEW_EMAIL));
        assertThat(email(accountId)).isEqualTo(NEW_EMAIL);

        // Heads-up lands at the OLD address; the token is single-use.
        MimeMessage[] all = awaitMessages(2);
        assertThat(all[1].getAllRecipients()[0].toString()).isEqualTo(OLD_EMAIL);
        assertThat(all[1].getSubject()).contains("changed");
        mvc.perform(post("/account/email-change/confirm").contentType(JSON)
                        .content("{\"token\":\"" + token + "\"}"))
                .andExpect(status().isBadRequest());

        // The new email logs in; the old one no longer exists.
        login(NEW_EMAIL);
        mvc.perform(post("/account/login").contentType(JSON)
                        .content("{\"email\":\"" + OLD_EMAIL + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void wrongPasswordIsRejectedAndSendsNothing() throws Exception {
        Cookie session = login(OLD_EMAIL);
        mvc.perform(post("/account/email-change/request").cookie(session).contentType(JSON)
                        .content("{\"new_email\":\"" + NEW_EMAIL + "\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized());
        assertThat(greenMail.getReceivedMessages()).isEmpty();
    }

    @Test
    void takenAddressGetsTheUniform202ButNoEmail() throws Exception {
        jdbc.update("""
                INSERT INTO accounts (id, email, password_hash, org_name, status, email_verified_at)
                VALUES (?, ?, ?, 'Occupant', 'ACTIVE', now())
                """, UUID.randomUUID(), NEW_EMAIL, Passwords.hash("occupant-pass-1"));

        Cookie session = login(OLD_EMAIL);
        mvc.perform(post("/account/email-change/request").cookie(session).contentType(JSON)
                        .content("{\"new_email\":\"" + NEW_EMAIL + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isAccepted());
        assertThat(greenMail.getReceivedMessages()).isEmpty();
        assertThat(email(accountId)).isEqualTo(OLD_EMAIL);
    }

    private MimeMessage[] awaitMessages(int count) {
        greenMail.waitForIncomingEmail(5000, count);
        MimeMessage[] messages = greenMail.getReceivedMessages();
        assertThat(messages).hasSizeGreaterThanOrEqualTo(count);
        return messages;
    }

    private String email(UUID id) {
        return jdbc.queryForObject("SELECT email FROM accounts WHERE id = ?", String.class, id);
    }

    private Cookie login(String email) throws Exception {
        MvcResult login = mvc.perform(post("/account/login").contentType(JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        Cookie cookie = login.getResponse().getCookie("zelo_session");
        assertThat(cookie).isNotNull();
        return cookie;
    }
}
