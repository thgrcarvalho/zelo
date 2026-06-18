package io.github.thgrcarvalho.zelo.application;

import io.github.thgrcarvalho.zelo.application.email.EmailSender;
import io.github.thgrcarvalho.zelo.application.error.BadRequestException;
import io.github.thgrcarvalho.zelo.application.error.ResourceNotFoundException;
import io.github.thgrcarvalho.zelo.application.error.ServiceUnavailableException;
import io.github.thgrcarvalho.zelo.application.error.UnauthorizedException;
import io.github.thgrcarvalho.zelo.domain.account.Account;
import io.github.thgrcarvalho.zelo.domain.account.AccountRepository;
import io.github.thgrcarvalho.zelo.domain.account.AccountToken;
import io.github.thgrcarvalho.zelo.domain.account.AccountTokenRepository;
import io.github.thgrcarvalho.zelo.domain.account.TokenPurpose;
import io.github.thgrcarvalho.zelo.domain.crypto.Hashes;
import io.github.thgrcarvalho.zelo.domain.crypto.Passwords;
import io.github.thgrcarvalho.zelo.domain.crypto.RawKeys;
import io.github.thgrcarvalho.zelo.infrastructure.config.ZeloProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Self-service account lifecycle for instant, email-verified onboarding: signup,
 * login, email verification, resend, and password reset. There is no operator and
 * no approval — verifying the emailed link is the only gate (UNVERIFIED → ACTIVE).
 *
 * <p>Security posture: passwords are PBKDF2-hashed; emails are stored and matched
 * lowercased. Verification/reset tokens are random, stored only as a SHA-256 hash,
 * single-use, purpose-bound, and short-lived. The flows are enumeration-safe — an
 * unknown email and a known one return the same thing, and the only side effect is
 * an out-of-band email — and rate-limited (a per-account cooldown + daily cap on
 * top of nginx's per-IP throttle). The session token is minted by the controller;
 * this service validates credentials and mints/redeems tokens.</p>
 *
 * <p>Email is SENT by the controller (via the async mailer) after this service's
 * transaction commits, so SMTP never runs inside a DB transaction and a link never
 * points at a token row that rolled back. These methods return the raw token to
 * dispatch ({@link MailDispatch}) rather than sending it.</p>
 */
@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    /**
     * A well-formed PBKDF2 hash of a value no one uses, verified against when the
     * email is unknown so a login costs the same work whether or not the account
     * exists — closing the timing side channel that would reveal which emails are
     * registered. Derived via {@link Passwords#hash} so it tracks the current
     * iteration count.
     */
    private static final String ABSENT_ACCOUNT_HASH = Passwords.hash("zelo-no-such-account");

    private final AccountRepository accounts;
    private final AccountTokenRepository tokens;
    private final EmailSender emailSender;
    private final ZeloProperties.Mail mail;

    public AccountService(AccountRepository accounts, AccountTokenRepository tokens,
                          EmailSender emailSender, ZeloProperties properties) {
        this.accounts = accounts;
        this.tokens = tokens;
        this.emailSender = emailSender;
        this.mail = properties.getMail();
    }

    /**
     * Register a new integrator. Enumeration-safe: returns the same dispatch shape
     * whether the email is fresh, already registered-but-unverified (a resend), or
     * already active (no email). A present {@link MailDispatch} means the controller
     * should send a verification email; empty means send nothing. Fail-closed: 503
     * when verification is required but mail is unconfigured (before any write).
     */
    @Transactional
    public Optional<MailDispatch> signup(String email, String rawPassword, String orgName) {
        boolean requireVerification = mail.isRequireVerification();
        if (requireVerification && !emailSender.isConfigured()) {
            throw new ServiceUnavailableException("Account signup is temporarily unavailable");
        }
        String normalized = normalizeEmail(email);
        Instant now = Instant.now();
        // Hash up front, unconditionally, so the request's PBKDF2 cost is identical
        // whether or not the email already exists — without this, only the new-account
        // path hashes and the latency difference is a network-measurable enumeration
        // oracle (the same channel authenticate() closes with ABSENT_ACCOUNT_HASH).
        // Discarded on the existing-account paths.
        String passwordHash = Passwords.hash(rawPassword);

        Account existing = accounts.findByEmail(normalized).orElse(null);
        if (existing != null) {
            // Never reveal that the email is taken. If it's an unverified account,
            // quietly re-send its verification link (throttled); otherwise send
            // nothing. Either way the HTTP response is identical. Log the id, not the
            // email, so server logs aren't a side-channel record of which emails exist.
            if (requireVerification && !existing.isVerified()
                    && !throttled(existing.getId(), TokenPurpose.EMAIL_VERIFICATION, now)) {
                return Optional.of(reissueVerification(existing.getId(), normalized, now));
            }
            log.info("Signup attempt for an existing account (id={}) — suppressed (enumeration-safe)",
                    existing.getId());
            return Optional.empty();
        }

        Account account = Account.signup(UUID.randomUUID(), normalized, passwordHash, orgName.trim(), now);
        if (!requireVerification) {
            // Mail off (dev): instant active, no email.
            account.markEmailVerified(now);
            accounts.save(account);
            log.info("Account signup (id={}) — ACTIVE (verification disabled)", account.getId());
            return Optional.empty();
        }
        accounts.save(account);
        String raw = mintToken(account.getId(), TokenPurpose.EMAIL_VERIFICATION,
                Duration.ofHours(mail.getVerificationTtlHours()), now);
        log.info("Account signup (id={}) — UNVERIFIED, verification email queued", account.getId());
        return Optional.of(new MailDispatch(normalized, raw));
    }

    /**
     * Verify credentials and return the account, or throw a generic 401. The same
     * error covers an unknown email and a wrong password (no enumeration); a dummy
     * hash is verified for unknown emails to equalize timing. Works for UNVERIFIED
     * accounts too — they get an inert session (the controller gates capabilities).
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

    /** Redeem an email-verification token: UNVERIFIED → ACTIVE. Generic 400 on a bad token. */
    @Transactional
    public Account verifyEmail(String rawToken) {
        Instant now = Instant.now();
        AccountToken token = redeem(rawToken, TokenPurpose.EMAIL_VERIFICATION, now);
        Account account = require(token.getAccountId());
        account.markEmailVerified(now);
        accounts.save(account);
        log.info("Account {} verified its email — ACTIVE", account.getId());
        return account;
    }

    /**
     * Re-send a verification email for the signed-in unverified account. Returns the
     * dispatch to send, or empty when already verified, throttled, or mail is off.
     * Caller is authenticated (no enumeration risk).
     */
    @Transactional
    public Optional<MailDispatch> resendVerification(UUID accountId) {
        Instant now = Instant.now();
        Account account = require(accountId);
        if (account.isVerified()) {
            return Optional.empty();
        }
        if (!emailSender.isConfigured()) {
            throw new ServiceUnavailableException("Email is temporarily unavailable");
        }
        if (throttled(accountId, TokenPurpose.EMAIL_VERIFICATION, now)) {
            return Optional.empty();
        }
        return Optional.of(reissueVerification(accountId, account.getEmail(), now));
    }

    /**
     * Begin a password reset. Always enumeration-safe: an unknown or throttled email
     * yields an empty dispatch (the controller still returns the uniform 204).
     * Fail-closed 503 when mail is unconfigured (a global signal, not per-account).
     */
    @Transactional
    public Optional<MailDispatch> requestPasswordReset(String email) {
        if (!emailSender.isConfigured()) {
            throw new ServiceUnavailableException("Password reset is temporarily unavailable");
        }
        Instant now = Instant.now();
        Account account = accounts.findByEmail(normalizeEmail(email)).orElse(null);
        if (account == null || throttled(account.getId(), TokenPurpose.PASSWORD_RESET, now)) {
            return Optional.empty();
        }
        tokens.invalidateOutstanding(account.getId(), TokenPurpose.PASSWORD_RESET, now);
        String raw = mintToken(account.getId(), TokenPurpose.PASSWORD_RESET,
                Duration.ofMinutes(mail.getResetTtlMinutes()), now);
        log.info("Password reset requested for account {}", account.getId());
        return Optional.of(new MailDispatch(account.getEmail(), raw));
    }

    /**
     * Complete a password reset: set the new password and advance the watermark so
     * every existing session is invalidated. Single-use, purpose-bound; generic 400
     * on a bad token. No session is issued (the user re-logs in with the new password).
     */
    @Transactional
    public void resetPassword(String rawToken, String newPassword) {
        Instant now = Instant.now();
        AccountToken token = redeem(rawToken, TokenPurpose.PASSWORD_RESET, now);
        Account account = require(token.getAccountId());
        account.changePassword(Passwords.hash(newPassword), now);
        accounts.save(account);
        log.info("Password reset completed for account {} — sessions invalidated", account.getId());
    }

    /** Load an account by id, or 404. */
    @Transactional(readOnly = true)
    public Account require(UUID id) {
        return accounts.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Account " + id + " not found"));
    }

    // --- internals ---------------------------------------------------------------

    /** Invalidate prior verification tokens and mint a fresh one. */
    private MailDispatch reissueVerification(UUID accountId, String email, Instant now) {
        tokens.invalidateOutstanding(accountId, TokenPurpose.EMAIL_VERIFICATION, now);
        String raw = mintToken(accountId, TokenPurpose.EMAIL_VERIFICATION,
                Duration.ofHours(mail.getVerificationTtlHours()), now);
        return new MailDispatch(email, raw);
    }

    /** Generate a raw token, store only its hash, return the raw value for the email link. */
    private String mintToken(UUID accountId, TokenPurpose purpose, Duration ttl, Instant now) {
        String raw = RawKeys.generateToken();
        tokens.save(AccountToken.issue(accountId, purpose, Hashes.sha256Hex(raw), now.plus(ttl), now));
        return raw;
    }

    /**
     * Look up, validate (purpose + expiry), and atomically consume a token. The
     * atomic {@code consume} (UPDATE ... WHERE used_at IS NULL) is the single-use
     * gate; every failure mode throws the same generic 400.
     */
    private AccountToken redeem(String rawToken, TokenPurpose purpose, Instant now) {
        AccountToken token = (rawToken == null || rawToken.isBlank())
                ? null
                : tokens.findByTokenHash(Hashes.sha256Hex(rawToken)).orElse(null);
        if (token == null || token.getPurpose() != purpose || token.isExpired(now)
                || tokens.consume(token.getId(), now) != 1) {
            throw new BadRequestException("This link is invalid or has expired");
        }
        return token;
    }

    /** True when an account has sent a token of this purpose too recently or too often. */
    private boolean throttled(UUID accountId, TokenPurpose purpose, Instant now) {
        Optional<AccountToken> last = tokens.findFirstByAccountIdAndPurposeOrderByCreatedAtDesc(accountId, purpose);
        if (last.isPresent() && last.get().getCreatedAt().isAfter(now.minusSeconds(mail.getResendCooldownSeconds()))) {
            return true;
        }
        int recent = tokens.countByAccountIdAndPurposeAndCreatedAtAfter(
                accountId, purpose, now.minus(Duration.ofHours(24)));
        return recent >= mail.getDailyEmailsPerAccount();
    }

    private static String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    /** An email to send out-of-band, after the transaction commits. */
    public record MailDispatch(String email, String rawToken) {
    }
}
