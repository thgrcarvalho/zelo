package io.github.thgrcarvalho.zelo.domain.account;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    Optional<Account> findByEmail(String email);

    /**
     * Atomically record a failed login: increment the counter and, once it reaches the
     * threshold, set the lockout. A native UPDATE (not a load-modify-save) so concurrent
     * failures can't drop an increment to an optimistic-lock conflict, and so it persists
     * even though the caller throws 401 — the service marks that path {@code noRollbackFor}.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE accounts
               SET failed_login_count = failed_login_count + 1,
                   locked_until = CASE WHEN failed_login_count + 1 >= :threshold
                                       THEN :lockUntil ELSE locked_until END
             WHERE id = :id""", nativeQuery = true)
    void recordFailedLogin(@Param("id") UUID id, @Param("threshold") int threshold,
                           @Param("lockUntil") Instant lockUntil);

    /** Clear the failure counter and lockout (a successful login or a password reset). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE accounts SET failed_login_count = 0, locked_until = NULL WHERE id = :id",
            nativeQuery = true)
    void clearFailedLogins(@Param("id") UUID id);
}
