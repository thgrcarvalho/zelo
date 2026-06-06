package io.github.thgrcarvalho.zelo.application;

import io.github.thgrcarvalho.zelo.domain.audit.AuditEntry;
import io.github.thgrcarvalho.zelo.domain.audit.AuditTrail;
import io.github.thgrcarvalho.zelo.domain.audit.ChainVerification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Read-side use cases over the audit trail: export and integrity verification. */
@Service
public class AuditService {

    private final AuditTrail auditTrail;

    public AuditService(AuditTrail auditTrail) {
        this.auditTrail = auditTrail;
    }

    @Transactional(readOnly = true)
    public List<AuditEntry> export(UUID apiKeyId, Instant from, Instant to) {
        return auditTrail.list(apiKeyId, from, to);
    }

    @Transactional(readOnly = true)
    public ChainVerification verify(UUID apiKeyId) {
        return auditTrail.verify(apiKeyId);
    }
}
