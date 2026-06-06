package io.github.thgrcarvalho.zelo.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.thgrcarvalho.zelo.application.error.ConflictException;
import io.github.thgrcarvalho.zelo.application.error.ResourceNotFoundException;
import io.github.thgrcarvalho.zelo.domain.audit.AuditEventType;
import io.github.thgrcarvalho.zelo.domain.audit.AuditTrail;
import io.github.thgrcarvalho.zelo.domain.subject.LegalBasis;
import io.github.thgrcarvalho.zelo.domain.subject.Purpose;
import io.github.thgrcarvalho.zelo.domain.subject.PurposeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class PurposeService {

    private final PurposeRepository purposes;
    private final AuditTrail auditTrail;
    private final ObjectMapper json;

    public PurposeService(PurposeRepository purposes, AuditTrail auditTrail, ObjectMapper json) {
        this.purposes = purposes;
        this.auditTrail = auditTrail;
        this.json = json;
    }

    @Transactional
    public Purpose create(UUID apiKeyId, String key, String description, LegalBasis legalBasis) {
        purposes.findByApiKeyIdAndKey(apiKeyId, key).ifPresent(p -> {
            throw new ConflictException("A purpose with key '" + key + "' already exists");
        });
        Instant now = Instant.now();
        Purpose purpose = new Purpose(UUID.randomUUID(), apiKeyId, key, description, legalBasis, now);
        purposes.save(purpose);
        auditTrail.append(apiKeyId, AuditEventType.PURPOSE_CREATED,
                json.createObjectNode()
                        .put("purposeId", purpose.getId().toString())
                        .put("purposeKey", key)
                        .put("legalBasis", legalBasis.name())
                        .put("description", description),
                now);
        return purpose;
    }

    @Transactional(readOnly = true)
    public List<Purpose> list(UUID apiKeyId) {
        return purposes.findByApiKeyIdOrderByCreatedAtAsc(apiKeyId);
    }

    @Transactional(readOnly = true)
    public Purpose require(UUID apiKeyId, String key) {
        return purposes.findByApiKeyIdAndKey(apiKeyId, key)
                .orElseThrow(() -> new ResourceNotFoundException("No purpose with key '" + key + "'"));
    }
}
