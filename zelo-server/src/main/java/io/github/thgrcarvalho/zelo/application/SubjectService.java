package io.github.thgrcarvalho.zelo.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.thgrcarvalho.zelo.application.error.ResourceNotFoundException;
import io.github.thgrcarvalho.zelo.domain.audit.AuditEventType;
import io.github.thgrcarvalho.zelo.domain.audit.AuditTrail;
import io.github.thgrcarvalho.zelo.domain.subject.Subject;
import io.github.thgrcarvalho.zelo.domain.subject.SubjectRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class SubjectService {

    private final SubjectRepository subjects;
    private final AuditTrail auditTrail;
    private final ObjectMapper json;

    public SubjectService(SubjectRepository subjects, AuditTrail auditTrail, ObjectMapper json) {
        this.subjects = subjects;
        this.auditTrail = auditTrail;
        this.json = json;
    }

    /** Get the subject, registering it (and auditing the registration) on first sight. */
    @Transactional
    public Subject upsert(UUID apiKeyId, String externalId) {
        return subjects.findByApiKeyIdAndExternalId(apiKeyId, externalId)
                .orElseGet(() -> register(apiKeyId, externalId));
    }

    private Subject register(UUID apiKeyId, String externalId) {
        Instant now = Instant.now();
        Subject subject = new Subject(UUID.randomUUID(), apiKeyId, externalId, now);
        // Flush now: the consent ledger (raw JDBC) inserts a row referencing this
        // subject within the same transaction, so the INSERT must already be on the
        // connection — a deferred JPA flush would trip the foreign key.
        subjects.saveAndFlush(subject);
        auditTrail.append(apiKeyId, AuditEventType.SUBJECT_REGISTERED,
                json.createObjectNode()
                        .put("subjectId", subject.getId().toString())
                        .put("externalId", externalId),
                now);
        return subject;
    }

    @Transactional(readOnly = true)
    public Subject require(UUID apiKeyId, String externalId) {
        return subjects.findByApiKeyIdAndExternalId(apiKeyId, externalId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No subject with external_id '" + externalId + "'"));
    }
}
