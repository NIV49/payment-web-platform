package com.niv.payment.permission;

import com.niv.payment.permission.domain.AccountDomain;
import com.niv.payment.permission.service.AuthenticationService;
import com.niv.payment.permission.service.AuthenticationService.AuthenticationFailedException;
import com.niv.payment.permission.service.AuthenticationService.CredentialAccount;
import com.niv.payment.permission.service.AuthenticationService.LoginCommand;
import com.niv.payment.permission.service.AuthenticationService.LoginSession;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class AuthenticationServiceTest {
    private static final String SUPPORTED_DUMMY_HASH =
        "$2a$12$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    @Test
    void limiterFailureStopsBeforeCredentialOrPasswordWork() {
        AtomicBoolean credentialLookupCalled = new AtomicBoolean();
        AuthenticationService service = new AuthenticationService(
            AccountDomain.PLATFORM,
            (username, domain) -> {
                credentialLookupCalled.set(true);
                return Optional.empty();
            },
            (raw, encoded) -> {
                fail("password verification must not run when rate-limit storage fails");
                return false;
            },
            new AuthenticationService.LoginAttemptLimiter() {
                public void acquire(String client, String username) {
                    throw new IllegalStateException("Redis unavailable");
                }
                public void recordSuccess(String client, String username) { fail("must not run"); }
            },
            authenticated -> { throw new AssertionError("session must not be issued"); },
            SUPPORTED_DUMMY_HASH
        );

        assertThrows(IllegalStateException.class,
            () -> service.login(new LoginCommand("admin", "correct", "ip-hash")));
        assertFalse(credentialLookupCalled.get());
    }

    @Test
    void successfulLoginUsesNormalizedUsernameAndClearsTheFailureBucket() {
        AtomicBoolean cleared = new AtomicBoolean();
        CredentialAccount account = new CredentialAccount(
            1, 2, 3, 4L, 5, 6, AccountDomain.PLATFORM, SUPPORTED_DUMMY_HASH);
        AuthenticationService service = new AuthenticationService(
            AccountDomain.PLATFORM,
            (username, domain) -> {
                assertEquals("admin", username);
                assertEquals(AccountDomain.PLATFORM, domain);
                return Optional.of(account);
            },
            (raw, encoded) -> raw.equals("correct") && encoded.equals(SUPPORTED_DUMMY_HASH),
            new AuthenticationService.LoginAttemptLimiter() {
                public void acquire(String client, String username) {
                    assertEquals("ip-hash", client);
                    assertEquals("admin", username);
                }
                public void recordSuccess(String client, String username) { cleared.set(true); }
            },
            authenticated -> new LoginSession("opaque-session-token"),
            SUPPORTED_DUMMY_HASH
        );

        LoginSession session = service.login(new LoginCommand(" ADMIN ", "correct", "ip-hash"));

        assertEquals("opaque-session-token", session.token());
        assertTrue(cleared.get());
    }

    @Test
    void unknownUserAndWrongPasswordHaveTheSamePublicFailure() {
        AtomicBoolean attemptReserved = new AtomicBoolean();
        AuthenticationService service = new AuthenticationService(
            AccountDomain.PLATFORM,
            (username, domain) -> Optional.empty(),
            (raw, encoded) -> false,
            new AuthenticationService.LoginAttemptLimiter() {
                public void acquire(String client, String username) { attemptReserved.set(true); }
                public void recordSuccess(String client, String username) {
                    fail("failed login must not release its reservation");
                }
            },
            authenticated -> { throw new AssertionError("session must not be issued"); },
            SUPPORTED_DUMMY_HASH
        );

        AuthenticationFailedException error = assertThrows(AuthenticationFailedException.class,
            () -> service.login(new LoginCommand("missing", "wrong", "ip-hash")));

        assertEquals("Invalid username or password", error.getMessage());
        assertTrue(attemptReserved.get());
    }

    @Test
    void serverFixedAccountDomainIsPassedToCredentialLookup() {
        CredentialAccount account = new CredentialAccount(
            1, 2, 9, 4L, 5, 6, AccountDomain.PLATFORM, SUPPORTED_DUMMY_HASH);
        AuthenticationService service = new AuthenticationService(
            AccountDomain.PLATFORM,
            (username, domain) -> {
                assertEquals("admin", username);
                assertEquals(AccountDomain.PLATFORM, domain);
                return Optional.of(account);
            },
            (raw, encoded) -> true,
            new AuthenticationService.LoginAttemptLimiter() {
                public void acquire(String client, String username) { }
                public void recordSuccess(String client, String username) { }
            },
            authenticated -> new LoginSession("selected-workspace-session"),
            SUPPORTED_DUMMY_HASH
        );

        LoginSession session = service.login(new LoginCommand("admin", "correct", "ip-hash"));

        assertEquals("selected-workspace-session", session.token());
    }

    @Test
    void unsupportedStoredHashFailsClosedWithoutReachingTheRealHashVerifier() {
        CredentialAccount account = new CredentialAccount(1, 2, 3, 4L, 5, 6, AccountDomain.PLATFORM,
            "$2a$99$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy");
        AtomicBoolean sessionIssued = new AtomicBoolean();
        AuthenticationService service = new AuthenticationService(
            AccountDomain.PLATFORM,
            (username, domain) -> Optional.of(account),
            (raw, encoded) -> {
                assertEquals(SUPPORTED_DUMMY_HASH, encoded);
                return false;
            },
            new AuthenticationService.LoginAttemptLimiter() {
                public void acquire(String client, String username) { }
                public void recordSuccess(String client, String username) { fail("must not run"); }
            },
            authenticated -> {
                sessionIssued.set(true);
                return new LoginSession("must-not-be-issued");
            },
            SUPPORTED_DUMMY_HASH
        );

        assertThrows(AuthenticationFailedException.class,
            () -> service.login(new LoginCommand("admin", "correct", "ip-hash")));
        assertFalse(sessionIssued.get());
    }

    @Test
    void adapterCannotReturnAnAccountFromAnotherDomain() {
        CredentialAccount merchant = new CredentialAccount(
            1, 2, 3, 4L, 5, 6, AccountDomain.MERCHANT, SUPPORTED_DUMMY_HASH);
        AuthenticationService service = new AuthenticationService(
            AccountDomain.PLATFORM,
            (username, domain) -> Optional.of(merchant),
            (raw, encoded) -> true,
            new AuthenticationService.LoginAttemptLimiter() {
                public void acquire(String client, String username) { }
                public void recordSuccess(String client, String username) { fail("must not run"); }
            },
            authenticated -> { throw new AssertionError("session must not be issued"); },
            SUPPORTED_DUMMY_HASH);

        assertThrows(AuthenticationFailedException.class,
            () -> service.login(new LoginCommand("admin", "correct", "ip-hash")));
    }
}
