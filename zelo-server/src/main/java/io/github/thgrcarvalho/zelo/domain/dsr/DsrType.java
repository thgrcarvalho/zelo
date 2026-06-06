package io.github.thgrcarvalho.zelo.domain.dsr;

/**
 * Data-subject-request type. v1 supports DELETE only (ACCESS, CORRECTION and
 * PORTABILITY are deferred). Maps to the {@code dsr_type} Postgres enum.
 */
public enum DsrType {
    DELETE
}
