package com.niv.payment.permission.security;

import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpLogic;
import com.niv.payment.permission.domain.AccountDomain;
import com.niv.payment.permission.service.AuthenticationService;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SaTokenSessionIssuerTest {
    private static final Instant NOW = Instant.parse("2026-08-13T10:00:00Z");

    @Test
    void localLoginAllowsAnAccountWithoutADepartment() {
        var stpLogic = new InMemoryStpLogic();
        var issuer = new SaTokenSessionIssuer(stpLogic, AccountDomain.PLATFORM,
            new SecureRandom(), Clock.fixed(NOW, ZoneOffset.UTC));
        var account = new AuthenticationService.CredentialAccount(
            10L, 20L, 30L, null, 7L, 3L, 11L, AccountDomain.PLATFORM, "password-hash");

        assertDoesNotThrow(() -> issuer.login(account));

        assertEquals(20L, stpLogic.loginId);
        assertNull(stpLogic.session.get(SessionAttributeNames.DEPARTMENT_ID));
        assertEquals(NOW.getEpochSecond(),
            stpLogic.session.get(SessionAttributeNames.STEP_UP_AT));
    }

    @Test
    void federatedLoginLeavesStepUpTimestampAbsentUntilStepUpSucceeds() {
        var stpLogic = new InMemoryStpLogic();
        var issuer = new SaTokenSessionIssuer(stpLogic, AccountDomain.PLATFORM);
        var principal = new FederatedSessionPrincipal(
            10L, 20L, 30L, null, 7L, 3L, 11L, AccountDomain.PLATFORM,
            "platform.localhost", "https://idp.example.test/realms/PLATFORM", "subject-10",
            "oidc-session-10", Instant.parse("2026-08-06T10:00:00Z"), "2", "signed-id-token");

        assertDoesNotThrow(() -> issuer.loginFederated(principal));

        assertEquals(20L, stpLogic.loginId);
        assertEquals("PLATFORM", stpLogic.session.get(SessionAttributeNames.ACCOUNT_DOMAIN));
        assertNull(stpLogic.session.get(SessionAttributeNames.STEP_UP_AT));
    }

    private static final class InMemoryStpLogic extends StpLogic {
        private final SaSession session = new SaSession("test-session");
        private Object loginId;

        private InMemoryStpLogic() {
            super("test-login-type");
        }

        @Override
        public void login(Object loginId) {
            this.loginId = loginId;
        }

        @Override
        public SaSession getSession() {
            return session;
        }

        @Override
        public String getTokenValue() {
            return "test-token";
        }
    }
}
