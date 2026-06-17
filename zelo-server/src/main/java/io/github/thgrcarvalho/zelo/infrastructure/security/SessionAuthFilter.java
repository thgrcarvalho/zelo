package io.github.thgrcarvalho.zelo.infrastructure.security;

import io.github.thgrcarvalho.zelo.domain.account.AccountRepository;
import io.github.thgrcarvalho.zelo.domain.crypto.SessionTokens;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Resolves the session cookie on {@code /account/**} into an {@link AccountPrincipal}
 * and attaches it to the request — but never rejects. Public endpoints
 * ({@code /account/signup}, {@code /account/login}) must pass through unauthenticated;
 * protected endpoints obtain the principal via {@link AccountPrincipalArgumentResolver},
 * which is what enforces 401 when it is absent. (Authorization — ACTIVE/OPERATOR
 * checks — happens in the controller.)
 *
 * <p>The token carries only the account id; this filter loads the account to stamp
 * the principal with the account's <em>current</em> role and status, so changes
 * apply on the next request without re-login. A valid-signature token whose account
 * no longer exists resolves to no principal.</p>
 */
public class SessionAuthFilter extends OncePerRequestFilter {

    public static final String PRINCIPAL_ATTRIBUTE = "zelo.accountPrincipal";
    public static final String SESSION_COOKIE = "zelo_session";

    private final SessionTokens sessionTokens;
    private final AccountRepository accounts;

    public SessionAuthFilter(SessionTokens sessionTokens, AccountRepository accounts) {
        this.sessionTokens = sessionTokens;
        this.accounts = accounts;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = readCookie(request, SESSION_COOKIE);
        if (token != null) {
            sessionTokens.verify(token).ifPresent(accountId ->
                    accounts.findById(accountId).ifPresent(account ->
                            request.setAttribute(PRINCIPAL_ATTRIBUTE,
                                    new AccountPrincipal(account.getId(), account.getRole(), account.getStatus()))));
        }
        chain.doFilter(request, response);
    }

    private static String readCookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                String value = cookie.getValue();
                return (value == null || value.isBlank()) ? null : value;
            }
        }
        return null;
    }
}
