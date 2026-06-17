package io.github.thgrcarvalho.zelo.domain.account;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    Optional<Account> findByEmail(String email);

    List<Account> findByStatusOrderByCreatedAtAsc(AccountStatus status);
}
