package io.github.thgrcarvalho.zelo.domain.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A self-service integrator account: the human-facing owner of one or more API
 * keys. Holds B2B contact data (email + org), never end-user PII. Approval-gated —
 * created {@code PENDING}, an operator moves it to {@code ACTIVE} (may then mint
 * keys) or {@code REJECTED}.
 *
 * <p>Mutable aggregate → JPA. {@code role}/{@code status} are stored as enum names
 * in {@code VARCHAR} columns (no native Postgres enum), matching the V8 schema.</p>
 */
@Entity
@Table(name = "accounts")
public class Account {

    @Id
    private UUID id;

    @Column(name = "email", nullable = false, unique = true, updatable = false)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "org_name", nullable = false)
    private String orgName;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false)
    private AccountRole role;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private AccountStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** When an operator decided this account (approve or reject); null while PENDING. */
    @Column(name = "approved_at")
    private Instant approvedAt;

    /** The operator account that decided; null while PENDING. */
    @Column(name = "approved_by")
    private UUID approvedBy;

    protected Account() {
        // for JPA
    }

    private Account(UUID id, String email, String passwordHash, String orgName,
                    AccountRole role, AccountStatus status, Instant createdAt) {
        this.id = id;
        this.email = email;
        this.passwordHash = passwordHash;
        this.orgName = orgName;
        this.role = role;
        this.status = status;
        this.createdAt = createdAt;
    }

    /** A freshly signed-up integrator: a {@code USER}, awaiting operator approval. */
    public static Account pending(UUID id, String email, String passwordHash, String orgName, Instant createdAt) {
        return new Account(id, email, passwordHash, orgName, AccountRole.USER, AccountStatus.PENDING, createdAt);
    }

    /** A seeded operator: already {@code ACTIVE}, works the approval queue. */
    public static Account operator(UUID id, String email, String passwordHash, String orgName, Instant createdAt) {
        return new Account(id, email, passwordHash, orgName, AccountRole.OPERATOR, AccountStatus.ACTIVE, createdAt);
    }

    /** PENDING → ACTIVE, recording the deciding operator. Caller guards the transition. */
    public void approve(UUID operatorId, Instant when) {
        this.status = AccountStatus.ACTIVE;
        this.approvedBy = operatorId;
        this.approvedAt = when;
    }

    /** PENDING → REJECTED, recording the deciding operator. Caller guards the transition. */
    public void reject(UUID operatorId, Instant when) {
        this.status = AccountStatus.REJECTED;
        this.approvedBy = operatorId;
        this.approvedAt = when;
    }

    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getOrgName() {
        return orgName;
    }

    public AccountRole getRole() {
        return role;
    }

    public AccountStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getApprovedAt() {
        return approvedAt;
    }

    public UUID getApprovedBy() {
        return approvedBy;
    }

    public boolean isActive() {
        return status == AccountStatus.ACTIVE;
    }

    public boolean isOperator() {
        return role == AccountRole.OPERATOR;
    }
}
