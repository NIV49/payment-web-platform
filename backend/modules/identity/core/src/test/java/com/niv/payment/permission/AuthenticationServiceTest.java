package com.niv.payment.permission;

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
    @Test
    void successfulLoginUsesNormalizedUsernameAndClearsTheFailureBucket() {
        AtomicBoolean cleared = new AtomicBoolean();
        CredentialAccount account = new CredentialAccount(1, 2, 3, 4L, 5, 6, "$hash");
        AuthenticationService service = new AuthenticationService(
            (username, tenantId) -> {
                assertEquals("admin", username);
                assertNull(tenantId);
                return Optional.of(account);
            },
            (raw, encoded) -> raw.equals("correct") && encoded.equals("$hash"),
            new AuthenticationService.LoginAttemptLimiter() {
                public void requireAllowed(String key) { assertTrue(key.endsWith(":admin")); }
                public void recordFailure(String key) { fail("must not record a successful login"); }
                public void clear(String key) { cleared.set(true); }
            },
            authenticated -> new LoginSession("opaque-session-token"),
            "$dummy"
        );

        LoginSession session = service.login(new LoginCommand(" ADMIN ", "correct", "ip-hash"));

        assertEquals("opaque-session-token", session.token());
        assertTrue(cleared.get());
    }

    @Test
    void unknownUserAndWrongPasswordHaveTheSamePublicFailure() {
        AtomicBoolean failureRecorded = new AtomicBoolean();
        AuthenticationService service = new AuthenticationService(
            (username, tenantId) -> Optional.empty(),
            (raw, encoded) -> false,
            new AuthenticationService.LoginAttemptLimiter() {
                public void requireAllowed(String key) { }
                public void recordFailure(String key) { failureRecorded.set(true); }
                public void clear(String key) { fail("failed login must not clear failures"); }
            },
            authenticated -> { throw new AssertionError("session must not be issued"); },
            "$dummy"
        );

        AuthenticationFailedException error = assertThrows(AuthenticationFailedException.class,
            () -> service.login(new LoginCommand("missing", "wrong", "ip-hash")));

        assertEquals("Invalid username or password", error.getMessage());
        assertTrue(failureRecorded.get());
    }

    @Test
    void explicitTenantSelectionIsPassedToCredentialLookup() {
        CredentialAccount account = new CredentialAccount(1, 2, 9, 4L, 5, 6, "$hash");
        AuthenticationService service = new AuthenticationService(
            (username, tenantId) -> {
                assertEquals("admin", username);
                assertEquals(9L, tenantId);
                return Optional.of(account);
            },
            (raw, encoded) -> true,
            new AuthenticationService.LoginAttemptLimiter() {
                public void requireAllowed(String key) { }
                public void recordFailure(String key) { fail("must not fail"); }
                public void clear(String key) { }
            },
            authenticated -> new LoginSession("selected-workspace-session"),
            "$dummy"
        );

        LoginSession session = service.login(new LoginCommand("admin", "correct", 9L, "ip-hash"));

        assertEquals("selected-workspace-session", session.token());
    }
}
