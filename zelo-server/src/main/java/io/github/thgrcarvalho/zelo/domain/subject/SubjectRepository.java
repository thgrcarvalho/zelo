package io.github.thgrcarvalho.zelo.domain.subject;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SubjectRepository extends JpaRepository<Subject, UUID> {

    Optional<Subject> findByApiKeyIdAndExternalId(UUID apiKeyId, String externalId);
}
