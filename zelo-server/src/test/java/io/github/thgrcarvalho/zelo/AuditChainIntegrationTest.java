package io.github.thgrcarvalho.zelo;

import io.github.thgrcarvalho.zelo.application.ConsentReport;
import io.github.thgrcarvalho.zelo.application.ConsentService;
import io.github.thgrcarvalho.zelo.application.PurposeService;
import io.github.thgrcarvalho.zelo.domain.apikey.ApiKeyRepository;
import io.github.thgrcarvalho.zelo.domain.audit.AuditTrail;
import io.github.thgrcarvalho.zelo.domain.audit.ChainVerification;
import io.github.thgrcarvalho.zelo.domain.consent.ConsentAction;
import io.github.thgrcarvalho.zelo.domain.crypto.Hashes;
import io.github.thgrcarvalho.zelo.domain.subject.LegalBasis;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The moat, exercised against a real Postgres: append-only ledger, hash-chained
 * audit log, and {@code verify} catching both tampering and chain breaks.
 */
@IntegrationTest
@TestPropertySource(properties = {
        "zelo.bootstrap.api-key=test-key",
        "zelo.bootstrap.name=test-integrator"
})
class AuditChainIntegrationTest extends AbstractIntegrationTest {

    @Autowired ApiKeyRepository apiKeys;
    @Autowired PurposeService purposeService;
    @Autowired ConsentService consentService;
    @Autowired AuditTrail auditTrail;
    @Autowired JdbcTemplate jdbc;

    private UUID apiKeyId;

    @BeforeEach
    void freshChain() {
        jdbc.execute("TRUNCATE audit_log, consent_events, subjects, purposes RESTART IDENTITY CASCADE");
        apiKeyId = apiKeys.findByKeyHash(Hashes.sha256Hex("test-key")).orElseThrow().getId();
    }

    @Test
    void recordsConsentAndVerifiesACleanChain() {
        purposeService.create(apiKeyId, "billing", "Billing and invoicing", LegalBasis.CONTRACT);
        consentService.record(apiKeyId, "user-1", "billing", ConsentAction.GRANT, "checkout");
        ConsentReport report =
                consentService.record(apiKeyId, "user-1", "billing", ConsentAction.WITHDRAW, "privacy-center");

        // Current state is the latest event: withdrawn.
        assertThat(report.current()).hasSize(1);
        assertThat(report.current().get(0).granted()).isFalse();
        assertThat(report.current().get(0).lastAction()).isEqualTo(ConsentAction.WITHDRAW);
        // Append-only: both consent events are retained in history.
        assertThat(report.history()).hasSize(2);

        // Chain: purpose.created, subject.registered, consent.granted, consent.withdrawn.
        ChainVerification verification = auditTrail.verify(apiKeyId);
        assertThat(verification.ok()).isTrue();
        assertThat(verification.entriesChecked()).isEqualTo(4);
        assertThat(verification.firstBrokenEntryId()).isNull();
    }

    @Test
    void detectsATamperedPayload() {
        purposeService.create(apiKeyId, "billing", "Billing", LegalBasis.CONTRACT);
        consentService.record(apiKeyId, "user-1", "billing", ConsentAction.GRANT, "checkout");
        assertThat(auditTrail.verify(apiKeyId).ok()).isTrue();

        Long grantedEntryId = jdbc.queryForObject(
                "SELECT id FROM audit_log WHERE api_key_id = ? AND event_type = 'consent.granted'",
                Long.class, apiKeyId);
        // Tamper with the stored payload, leaving entry_hash untouched.
        jdbc.update("UPDATE audit_log SET payload = '{\"tampered\":true}'::jsonb WHERE id = ?", grantedEntryId);

        ChainVerification verification = auditTrail.verify(apiKeyId);
        assertThat(verification.ok()).isFalse();
        assertThat(verification.firstBrokenEntryId()).isEqualTo(grantedEntryId);
        assertThat(verification.detail()).contains("tampered");
    }

    @Test
    void detectsADeletedEntryViaBrokenLinkage() {
        purposeService.create(apiKeyId, "billing", "Billing", LegalBasis.CONTRACT);
        consentService.record(apiKeyId, "user-1", "billing", ConsentAction.GRANT, "checkout");

        Long subjectEntryId = jdbc.queryForObject(
                "SELECT id FROM audit_log WHERE api_key_id = ? AND event_type = 'subject.registered'",
                Long.class, apiKeyId);
        // Excise a middle entry: the following entry's prev_hash no longer matches.
        jdbc.update("DELETE FROM audit_log WHERE id = ?", subjectEntryId);

        ChainVerification verification = auditTrail.verify(apiKeyId);
        assertThat(verification.ok()).isFalse();
        assertThat(verification.detail()).contains("chain link");
    }
}
