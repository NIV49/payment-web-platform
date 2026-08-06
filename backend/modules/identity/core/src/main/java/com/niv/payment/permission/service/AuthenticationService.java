package com.niv.payment.permission.service;

import com.niv.payment.permission.domain.AccountDomain;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

public final class AuthenticationService {
    private final CredentialLookup credentials;
    private final PasswordVerifier passwordVerifier;
    private final LoginAttemptLimiter limiter;
    private final SessionIssuer sessions;
    private final String dummyPasswordHash;
    private final AccountDomain accountDomain;

    public AuthenticationService(AccountDomain accountDomain,
                                 CredentialLookup credentials,
                                 PasswordVerifier passwordVerifier,
                                 LoginAttemptLimiter limiter,
                                 SessionIssuer sessions,
                                 String dummyPasswordHash) {
        this.accountDomain = Objects.requireNonNull(accountDomain, "accountDomain");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.passwordVerifier = Objects.requireNonNull(passwordVerifier, "passwordVerifier");
        this.limiter = Objects.requireNonNull(limiter, "limiter");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.dummyPasswordHash = Objects.requireNonNull(dummyPasswordHash, "dummyPasswordHash");
        if (!LoginCredentialPolicy.isLoginCapableHash(dummyPasswordHash)) {
            throw new IllegalArgumentException("Dummy password hash must be a supported BCrypt credential");
        }
    }

    public LoginSession login(LoginCommand command) {
        Objects.requireNonNull(command, "command");
        String username = normalizeUsername(command.username());
        limiter.acquire(command.clientKey(), username);

        Optional<CredentialAccount> found = credentials.findActiveByUsername(username, accountDomain)
            .filter(account -> account.accountDomain() == accountDomain);
        Optional<CredentialAccount> loginCapable = found.filter(account ->
            LoginCredentialPolicy.isLoginCapableHash(account.passwordHash()));
        String encodedPassword = loginCapable.map(CredentialAccount::passwordHash).orElse(dummyPasswordHash);
        boolean passwordMatches;
        try {
            passwordMatches = passwordVerifier.matches(command.password(), encodedPassword);
        } catch (IllegalArgumentException exception) {
            passwordMatches = false;
        }
        if (loginCapable.isEmpty() || !passwordMatches) {
            throw new AuthenticationFailedException();
        }

        limiter.recordSuccess(command.clientKey(), username);
        credentials.markLoginSucceeded(loginCapable.get().userId());
        return sessions.login(loginCapable.get());
    }

    public void logout() {
        sessions.logout();
    }

    private static String normalizeUsername(String value) {
        if (value == null) {
            throw new AuthenticationFailedException();
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty() || normalized.length() > 100) {
            throw new AuthenticationFailedException();
        }
        return normalized;
    }

    public record LoginCommand(String username, String password, String clientKey) {
        public LoginCommand {
            Objects.requireNonNull(password, "password");
            Objects.requireNonNull(clientKey, "clientKey");
        }
    }

    public record CredentialAccount(long userId,
                                    long membershipId,
                                    long tenantId,
                                    Long departmentId,
                                    long permissionVersion,
                                    long sessionVersion,
                                    long identityVersion,
                                    AccountDomain accountDomain,
                                    String passwordHash) {
        public CredentialAccount {
            if (userId <= 0 || membershipId <= 0 || tenantId <= 0
                || permissionVersion < 0 || sessionVersion < 0 || identityVersion < 0) {
                throw new IllegalArgumentException("Credential identity is invalid");
            }
            Objects.requireNonNull(accountDomain, "accountDomain");
            Objects.requireNonNull(passwordHash, "passwordHash");
        }
    }

    public record LoginSession(String token) {
        public LoginSession {
            if (token == null || token.isBlank()) {
                throw new IllegalArgumentException("Session token is required");
            }
        }
    }

    @FunctionalInterface
    public interface CredentialLookup {
        Optional<CredentialAccount> findActiveByUsername(String normalizedUsername, AccountDomain accountDomain);

        default void markLoginSucceeded(long userId) {
        }
    }

    @FunctionalInterface
    public interface PasswordVerifier {
        boolean matches(String rawPassword, String encodedPassword);
    }

    public interface LoginAttemptLimiter {
        /** Atomically reserves capacity in both the client and client/username failure buckets. */
        void acquire(String clientKey, String normalizedUsername);

        /** Releases the successful attempt and resets this client/username's prior failures. */
        void recordSuccess(String clientKey, String normalizedUsername);
    }

    @FunctionalInterface
    public interface SessionIssuer {
        LoginSession login(CredentialAccount account);

        default void logout() {
        }
    }

    public static final class AuthenticationFailedException extends RuntimeException {
        public AuthenticationFailedException() {
            super("Invalid username or password");
        }
    }

    public static final class RateLimitExceededException extends RuntimeException {
        public RateLimitExceededException() {
            super("Too many login attempts");
        }
    }
}
