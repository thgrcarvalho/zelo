package io.github.thgrcarvalho.zelo.infrastructure.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.thgrcarvalho.zelo.domain.apikey.ApiKey;
import io.github.thgrcarvalho.zelo.domain.apikey.ApiKeyRepository;
import io.github.thgrcarvalho.zelo.domain.audit.AuditEventType;
import io.github.thgrcarvalho.zelo.domain.audit.AuditTrail;
import io.github.thgrcarvalho.zelo.domain.crypto.Hashes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Seeds — and holds the identity of — a synthetic "showcase" audit chain that the
 * public, unauthenticated {@code GET /v1/audit/verify/demo} endpoint verifies for
 * the landing page's live proof widget.
 *
 * <p>The chain belongs to a dedicated {@link ApiKey} with a fixed id and an
 * unguessable (discarded-preimage) key hash, so it can never authenticate and is
 * fully isolated from real tenants. Its entries are fabricated — no real personal
 * data — and the endpoint exposes only the integrity verdict + a count, never any
 * payload, so publishing it leaks nothing.
 *
 * <p>Seeding is idempotent: the key is created once (looked up by id) and the demo
 * entries are appended only when the chain is empty. Any failure is swallowed — a
 * marketing widget must never block startup; the endpoint then simply reports an
 * empty (and so trivially valid) chain.
 */
@Component
@ConditionalOnProperty(prefix = "zelo.showcase", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ShowcaseChain implements ApplicationRunner {

    /** Stable id of the showcase integrator's chain — referenced by the public verify endpoint. */
    public static final UUID API_KEY_ID = UUID.fromString("5ec0de01-0000-4000-8000-000000000001");

    private static final Logger log = LoggerFactory.getLogger(ShowcaseChain.class);
    private static final String SUBJECT = "demo-subject-001";
    private static final String REQUEST = "demo-dsr-001";

    private final ApiKeyRepository apiKeys;
    private final AuditTrail auditTrail;
    private final ObjectMapper json;
    private final TransactionTemplate tx;

    public ShowcaseChain(ApiKeyRepository apiKeys, AuditTrail auditTrail, ObjectMapper json,
                         PlatformTransactionManager txManager) {
        this.apiKeys = apiKeys;
        this.auditTrail = auditTrail;
        this.json = json;
        this.tx = new TransactionTemplate(txManager);
    }

    @Override
    public void run(ApplicationArguments args) {
        ensure();
    }

    /**
     * Idempotently ensure the showcase key exists and its demo chain is seeded.
     * Safe to call repeatedly (e.g. from a test after it has truncated the log).
     */
    public void ensure() {
        try {
            ensureKey();
            // Seed atomically: the emptiness check + all 8 appends commit together, so a
            // crash mid-seed rolls back to an empty chain that re-seeds cleanly next boot —
            // no half-seeded chain can wedge the `== 0` guard at a count below 8. Running
            // verify() inside this transaction also honours its streaming-read contract.
            // (Seeding assumes a single instance, like the bootstrap-key seeder.)
            tx.executeWithoutResult(status -> {
                if (auditTrail.verify(API_KEY_ID).entriesChecked() == 0) {
                    seed();
                    log.info("Seeded showcase audit chain (8 entries) under api_key {}", API_KEY_ID);
                }
            });
        } catch (Exception e) {
            // Never let a demo widget block startup; the endpoint will report an empty chain.
            log.warn("Showcase chain seeding skipped: {}", e.toString());
        }
    }

    private void ensureKey() {
        if (apiKeys.findById(API_KEY_ID).isPresent()) {
            return;
        }
        // Random preimage, immediately discarded: the hash is stored but the raw key is
        // unguessable, so this row can never authenticate. It exists only to scope the chain.
        String keyHash = Hashes.sha256Hex(UUID.randomUUID().toString());
        apiKeys.save(new ApiKey(API_KEY_ID, keyHash, "Zelo showcase chain",
                null, null, "showcase", Instant.now()));
    }

    /** A complete LGPD lifecycle for one synthetic subject: register → consent → withdraw → erase. */
    private void seed() {
        Instant t = Instant.parse("2026-01-02T12:00:00Z");
        append(AuditEventType.SUBJECT_REGISTERED, t,
                json.createObjectNode().put("externalId", SUBJECT));
        append(AuditEventType.PURPOSE_CREATED, t.plus(1, ChronoUnit.MINUTES),
                json.createObjectNode().put("purposeKey", "marketing").put("legalBasis", "CONSENT"));
        append(AuditEventType.CONSENT_GRANTED, t.plus(2, ChronoUnit.MINUTES), consent("marketing"));
        append(AuditEventType.CONSENT_GRANTED, t.plus(3, ChronoUnit.MINUTES), consent("analytics"));
        append(AuditEventType.CONSENT_WITHDRAWN, t.plus(4, ChronoUnit.MINUTES), consent("marketing"));
        append(AuditEventType.DSR_DELETE_REQUESTED, t.plus(5, ChronoUnit.MINUTES),
                dsr().put("channel", "self-service"));
        append(AuditEventType.DSR_DELETE_DISPATCHED, t.plus(6, ChronoUnit.MINUTES), dsr());
        append(AuditEventType.DSR_DELETE_FULFILLED, t.plus(7, ChronoUnit.MINUTES),
                dsr().put("deletedRows", 1));
    }

    private ObjectNode consent(String purposeKey) {
        return json.createObjectNode()
                .put("externalId", SUBJECT)
                .put("purposeKey", purposeKey)
                .put("source", "web");
    }

    private ObjectNode dsr() {
        return json.createObjectNode()
                .put("externalId", SUBJECT)
                .put("requestId", REQUEST);
    }

    private void append(String eventType, Instant occurredAt, ObjectNode payload) {
        auditTrail.append(API_KEY_ID, eventType, payload, occurredAt);
    }
}
