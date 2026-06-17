package io.github.thgrcarvalho.zelo.application;

import io.github.thgrcarvalho.zelo.application.error.ConflictException;
import io.github.thgrcarvalho.zelo.application.error.ResourceNotFoundException;
import io.github.thgrcarvalho.zelo.application.error.UnauthorizedException;
import io.github.thgrcarvalho.zelo.domain.account.Account;
import io.github.thgrcarvalho.zelo.domain.account.AccountRepository;
import io.github.thgrcarvalho.zelo.domain.account.AccountStatus;
import io.github.thgrcarvalho.zelo.domain.crypto.Passwords;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Self-service account lifecycle: signup, login (credential check), and the
 * operator approval queue. Passwords are PBKDF2-hashed; emails are stored and
 * matched lowercased. Authentication failures are deliberately generic (no account
 * enumeration). The session token itself is minted by the controller — this
 * service only validates credentials and returns the account.
 */
@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    /**
     * A well-formed PBKDF2 hash of a value no one uses, verified against when the
     * email is unknown so a login attempt costs the same work whether or not the
     * account exists — closing the timing side channel that would otherwise reveal
     * which emails are registered. Generated via {@link Passwords#hash} so it always
     * uses the same (current) iteration count as real password hashes, keeping the
     * timing parity intact if the cost parameters change.
     */
    private static final String ABSENT_ACCOUNT_HASH = Passwords.hash("zelo-no-such-account");

    private final AccountRepository accounts;

    public AccountService(AccountRepository accounts) {
        this.accounts = accounts;
    }

    /** Register a new integrator. Returns the account, PENDING and awaiting approval. */
    @Transactional
    public Account signup(String email, String rawPassword, String orgName) {
        String normalized = normalizeEmail(email);
        if (accounts.findByEmail(normalized).isPresent()) {
            throw new ConflictException("An account with this email already exists");
        }
        Account account = Account.pending(UUID.randomUUID(), normalized,
                Passwords.hash(rawPassword), orgName.trim(), Instant.now());
        accounts.save(account);
        log.info("Account signup '{}' (id={}, org='{}') — PENDING", normalized, account.getId(), account.getOrgName());
        return account;
    }

    /**
     * Verify credentials and return the account, or throw a generic 401. The same
     * error is used for an unknown email and a wrong password (no enumeration), and
     * a dummy hash is verified for unknown emails to equalize timing.
     */
    @Transactional(readOnly = true)
    public Account authenticate(String email, String rawPassword) {
        Account account = accounts.findByEmail(normalizeEmail(email)).orElse(null);
        String hashToCheck = (account == null) ? ABSENT_ACCOUNT_HASH : account.getPasswordHash();
        boolean ok = Passwords.verify(rawPassword, hashToCheck);
        if (account == null || !ok) {
            throw new UnauthorizedException("Invalid email or password");
        }
        return account;
    }

    /** Load an account by id, or 404. */
    @Transactional(readOnly = true)
    public Account require(UUID id) {
        return accounts.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Account " + id + " not found"));
    }

    /** The approval queue: accounts awaiting an operator decision, oldest first. */
    @Transactional(readOnly = true)
    public List<Account> pending() {
        return accounts.findByStatusOrderByCreatedAtAsc(AccountStatus.PENDING);
    }

    /**
     * Operator action: PENDING/REJECTED → ACTIVE. Accepting a REJECTED account
     * gives a recovery path (an operator can reverse a mistaken rejection); 409 only
     * if the account is already ACTIVE.
     */
    @Transactional
    public Account approve(UUID operatorId, UUID accountId) {
        Account account = require(accountId);
        if (account.getStatus() == AccountStatus.ACTIVE) {
            throw new ConflictException("Account is already active");
        }
        account.approve(operatorId, Instant.now());
        accounts.save(account);
        log.info("Account {} approved by operator {}", accountId, operatorId);
        return account;
    }

    /** Operator action: PENDING → REJECTED. 409 if the account isn't pending. */
    @Transactional
    public Account reject(UUID operatorId, UUID accountId) {
        Account account = requirePending(accountId);
        account.reject(operatorId, Instant.now());
        accounts.save(account);
        log.info("Account {} rejected by operator {}", accountId, operatorId);
        return account;
    }

    private Account requirePending(UUID accountId) {
        Account account = require(accountId);
        if (account.getStatus() != AccountStatus.PENDING) {
            throw new ConflictException("Account is not awaiting approval (status " + account.getStatus() + ")");
        }
        return account;
    }

    private static String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }
}
