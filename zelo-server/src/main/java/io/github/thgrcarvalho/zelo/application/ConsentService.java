package io.github.thgrcarvalho.zelo.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.thgrcarvalho.zelo.domain.audit.AuditEventType;
import io.github.thgrcarvalho.zelo.domain.audit.AuditTrail;
import io.github.thgrcarvalho.zelo.domain.consent.ConsentAction;
import io.github.thgrcarvalho.zelo.domain.consent.ConsentEvent;
import io.github.thgrcarvalho.zelo.domain.consent.ConsentLedger;
import io.github.thgrcarvalho.zelo.domain.consent.ConsentStatus;
import io.github.thgrcarvalho.zelo.domain.subject.Purpose;
import io.github.thgrcarvalho.zelo.domain.subject.PurposeRepository;
import io.github.thgrcarvalho.zelo.domain.subject.Subject;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ConsentService {

    private final ConsentLedger ledger;
    private final AuditTrail auditTrail;
    private final SubjectService subjectService;
    private final PurposeService purposeService;
    private final PurposeRepository purposeRepository;
    private final ObjectMapper json;

    public ConsentService(ConsentLedger ledger, AuditTrail auditTrail, SubjectService subjectService,
                          PurposeService purposeService, PurposeRepository purposeRepository, ObjectMapper json) {
        this.ledger = ledger;
        this.auditTrail = auditTrail;
        this.subjectService = subjectService;
        this.purposeService = purposeService;
        this.purposeRepository = purposeRepository;
        this.json = json;
    }

    /** Record a GRANT/WITHDRAW (append-only) and return the resulting state + history. */
    @Transactional
    public ConsentReport record(UUID apiKeyId, String externalId, String purposeKey,
                                ConsentAction action, String source) {
        return record(apiKeyId, externalId, purposeKey, action, source, null);
    }

    /**
     * As {@link #record(UUID, String, String, ConsentAction, String)} but with optional
     * PII-free {@code metadata}, stored on the ledger event <em>and</em> folded into the
     * audit payload so it is covered by the tamper-evident hash chain.
     */
    @Transactional
    public ConsentReport record(UUID apiKeyId, String externalId, String purposeKey,
                                ConsentAction action, String source, Map<String, Object> metadata) {
        Subject subject = subjectService.upsert(apiKeyId, externalId);
        Purpose purpose = purposeService.require(apiKeyId, purposeKey);
        Instant now = Instant.now();

        JsonNode metaNode = (metadata == null || metadata.isEmpty()) ? null : json.valueToTree(metadata);
        ledger.append(new ConsentEvent(null, subject.getId(), purpose.getId(), action, source, now, metaNode));

        String eventType = action == ConsentAction.GRANT
                ? AuditEventType.CONSENT_GRANTED
                : AuditEventType.CONSENT_WITHDRAWN;
        ObjectNode payload = json.createObjectNode()
                .put("externalId", externalId)
                .put("purposeKey", purposeKey)
                .put("source", source);
        if (metaNode != null) {
            payload.set("metadata", metaNode);
        }
        auditTrail.append(apiKeyId, eventType, payload, now);

        return report(apiKeyId, subject, externalId, null, null);
    }

    @Transactional(readOnly = true)
    public ConsentReport getConsents(UUID apiKeyId, String externalId, String purposeKey) {
        Subject subject = subjectService.require(apiKeyId, externalId);
        UUID purposeId = purposeKey == null ? null : purposeService.require(apiKeyId, purposeKey).getId();
        return report(apiKeyId, subject, externalId, purposeKey, purposeId);
    }

    private ConsentReport report(UUID apiKeyId, Subject subject, String externalId,
                                 String purposeKeyFilter, UUID purposeIdFilter) {
        Map<UUID, String> keyById = purposeRepository.findByApiKeyIdOrderByCreatedAtAsc(apiKeyId).stream()
                .collect(Collectors.toMap(Purpose::getId, Purpose::getKey));

        List<ConsentStatus> current = ledger.currentState(subject.getId());
        if (purposeKeyFilter != null) {
            current = current.stream().filter(s -> s.purposeKey().equals(purposeKeyFilter)).toList();
        }

        List<ConsentHistoryItem> history = ledger.history(subject.getId(), purposeIdFilter).stream()
                .map(e -> new ConsentHistoryItem(
                        keyById.getOrDefault(e.purposeId(), e.purposeId().toString()),
                        e.action(), e.source(), e.occurredAt()))
                .toList();

        return new ConsentReport(externalId, current, history);
    }
}
