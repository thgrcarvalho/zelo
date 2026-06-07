package io.github.thgrcarvalho.zelo;

import io.github.thgrcarvalho.zelo.application.DsrService;
import io.github.thgrcarvalho.zelo.application.OverdueSweepService;
import io.github.thgrcarvalho.zelo.domain.apikey.ApiKeyRepository;
import io.github.thgrcarvalho.zelo.domain.audit.AuditTrail;
import io.github.thgrcarvalho.zelo.domain.crypto.Hashes;
import io.github.thgrcarvalho.zelo.domain.dsr.DsrRequest;
import io.github.thgrcarvalho.zelo.domain.dsr.DsrStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The overdue sweep: open requests past their deadline become OVERDUE (audited),
 * while future-deadline and already-fulfilled requests are left alone.
 */
@IntegrationTest
@TestPropertySource(properties = {
        "zelo.bootstrap.api-key=test-key",
        "zelo.bootstrap.name=test-integrator",
        // Push the scheduled sweep far out so only the explicit sweep() in the test runs.
        "zelo.dsr.overdue-sweep-interval-ms=3600000"
})
class OverdueSweepIntegrationTest extends AbstractIntegrationTest {

    @Autowired ApiKeyRepository apiKeys;
    @Autowired DsrService dsrService;
    @Autowired OverdueSweepService sweep;
    @Autowired AuditTrail auditTrail;
    @Autowired JdbcTemplate jdbc;

    private UUID apiKeyId;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE audit_log, consent_events, dsr_requests, outbox_event, subjects, purposes "
                + "RESTART IDENTITY CASCADE");
        apiKeyId = apiKeys.findByKeyHash(Hashes.sha256Hex("test-key")).orElseThrow().getId();
    }

    @Test
    void flagsAPastDeadlineRequestOverdueAndAuditsIt() {
        DsrRequest request = dsrService.createDeletionRequest(apiKeyId, "user-1");
        backdateDeadline(request.getId());

        assertThat(sweep.sweep()).isEqualTo(1);

        assertThat(dsrService.get(apiKeyId, request.getId()).getStatus()).isEqualTo(DsrStatus.OVERDUE);
        assertThat(auditTrail.verify(apiKeyId).ok()).isTrue();
        Integer overdueEvents = jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE event_type = 'dsr.delete.overdue'", Integer.class);
        assertThat(overdueEvents).isEqualTo(1);
    }

    @Test
    void leavesAFutureDeadlineRequestAlone() {
        DsrRequest request = dsrService.createDeletionRequest(apiKeyId, "user-2");

        assertThat(sweep.sweep()).isZero();
        assertThat(dsrService.get(apiKeyId, request.getId()).getStatus()).isNotEqualTo(DsrStatus.OVERDUE);
    }

    @Test
    void doesNotSweepAFulfilledRequestEvenIfPastDeadline() {
        DsrRequest request = dsrService.createDeletionRequest(apiKeyId, "user-3");
        dsrService.fulfill(apiKeyId, request.getId(), Map.of("deletedRows", 1));
        backdateDeadline(request.getId());

        assertThat(sweep.sweep()).isZero();
        assertThat(dsrService.get(apiKeyId, request.getId()).getStatus()).isEqualTo(DsrStatus.FULFILLED);
    }

    @Test
    void bumpsTheOptimisticLockVersionOnEachTransition() {
        DsrRequest request = dsrService.createDeletionRequest(apiKeyId, "user-v");
        long created = version(request.getId());

        dsrService.fulfill(apiKeyId, request.getId(), Map.of("deletedRows", 1));

        assertThat(version(request.getId())).isGreaterThan(created);
    }

    private long version(UUID requestId) {
        return jdbc.queryForObject("SELECT version FROM dsr_requests WHERE id = ?", Long.class, requestId);
    }

    private void backdateDeadline(UUID requestId) {
        jdbc.update("UPDATE dsr_requests SET deadline_at = now() - interval '1 day' WHERE id = ?", requestId);
    }
}
