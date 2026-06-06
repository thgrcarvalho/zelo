package io.github.thgrcarvalho.zelo.domain.subject;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PurposeRepository extends JpaRepository<Purpose, UUID> {

    Optional<Purpose> findByApiKeyIdAndKey(UUID apiKeyId, String key);

    List<Purpose> findByApiKeyIdOrderByCreatedAtAsc(UUID apiKeyId);
}
