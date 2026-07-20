package com.niv.payment.permission.service;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

public final class AuthenticationService {
    private final CredentialLookup credentials;
    private final PasswordVerifier passwordVerifier;
    private final LoginAttemptLimiter limiter;
    private final SessionIssuer sessions;
    private final String dummyPasswordHash;

    public AuthenticationService(CredentialLookup credentials,
                                 PasswordVerifier passwordVerifier,
                                 LoginAttemptLimiter limiter,
                                 SessionIssuer sessions,
                                 String dummyPasswordHash) {
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.passwordVerifier = Objects.requireNonNull(passwordVerifier, "passwordVerifier");
        this.limiter = Objects.requireNonNull(limiter, "limiter");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.dummyPasswordHash = Objects.requireNonNull(dummyPasswordHash, "dummyPasswordHash");
    }

    public LoginSession login(LoginCommand command) {
        Objects.requireNonNull(command, "command");
        String username = normalizeUsername(command.username());
        String bucketKey = command.clientKey() + ":" + username;
        limiter.requireAllowed(bucketKey);

        Optional<CredentialAccount> found = credentials.findActiveByUsername(username, command.tenantId());
        String encodedPassword = found.map(CredentialAccount::passwordHash).orElse(dummyPasswordHash);
        boolean passwordMatches = passwordVerifier.matches(command.password(), encodedPassword);
        if (found.isEmpty() || !passwordMatches) {
            limiter.recordFailure(bucketKey);
            throw new AuthenticationFailedException();
        }

        limiter.clear(bucketKey);
        credentials.markLoginSucceeded(found.get().userId());
        return sessions.login(found.get());
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

    public record LoginCommand(String username, String password, Long tenantId, String clientKey) {
        public LoginCommand {
            Objects.requireNonNull(password, "password");
            Objects.requireNonNull(clientKey, "clientKey");
            if (tenantId != null && tenantId <= 0) {
                throw new AuthenticationFailedException();
            }
        }

        public LoginCommand(String username, String password, String clientKey) {
            this(username, password, null, clientKey);
        }
    }

    public record CredentialAccount(long userId,
                                    long membershipId,
                                    long tenantId,
                                    Long departmentId,
                                    long permissionVersion,
                                    long sessionVersion,
                                    String passwordHash) {
        public CredentialAccount {
            if (userId <= 0 || membershipId <= 0 || tenantId <= 0
                || permissionVersion < 0 || sessionVersion < 0) {
                throw new IllegalArgumentException("Credential identity is invalid");
            }
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
        Optional<CredentialAccount> findActiveByUsername(String normalizedUsername, Long tenantId);

        default void markLoginSucceeded(long userId) {
        }
    }

    @FunctionalInterface
    public interface PasswordVerifier {
        boolean matches(String rawPassword, String encodedPassword);
    }

    public interface LoginAttemptLimiter {
        void requireAllowed(String bucketKey);

        void recordFailure(String bucketKey);

        void clear(String bucketKey);
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
