package com.niv.payment.permission;

import com.niv.payment.permission.security.SaTokenFacade;
import com.niv.payment.permission.security.SaTokenSessionBridge;
import com.niv.payment.permission.security.SessionAttributeNames;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SaTokenSessionBridgeTest {

    @Test
    void mapsOnlyTrustedServerSessionAttributesToAuthorizationSubject() {
        Map<String, Object> attributes = Map.of(
            SessionAttributeNames.USER_ID, 10L,
            SessionAttributeNames.MEMBERSHIP_ID, 20L,
            SessionAttributeNames.TENANT_ID, 30L,
            SessionAttributeNames.DEPARTMENT_ID, 40L,
            SessionAttributeNames.PERMISSION_VERSION, 7L,
            SessionAttributeNames.SESSION_VERSION, 3L,
            SessionAttributeNames.STEP_UP_VERIFIED, true);
        SaTokenFacade facade = new FakeSaTokenFacade(attributes);

        var subject = new SaTokenSessionBridge(facade, (tenantId, membershipId) -> 3L).currentSubject();

        assertEquals(20L, subject.membershipId());
        assertEquals(30L, subject.tenantId());
        assertEquals(7L, subject.permissionVersion());
    }

    @Test
    void rejectsARevokedSessionVersion() {
        Map<String, Object> attributes = Map.of(
            SessionAttributeNames.USER_ID, 10L,
            SessionAttributeNames.MEMBERSHIP_ID, 20L,
            SessionAttributeNames.TENANT_ID, 30L,
            SessionAttributeNames.PERMISSION_VERSION, 7L,
            SessionAttributeNames.SESSION_VERSION, 3L,
            SessionAttributeNames.STEP_UP_VERIFIED, false);

        var bridge = new SaTokenSessionBridge(new FakeSaTokenFacade(attributes),
            (tenantId, membershipId) -> 4L);

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, bridge::currentSubject);
    }

    private record FakeSaTokenFacade(Map<String, Object> attributes) implements SaTokenFacade {
        @Override
        public boolean isLoggedIn() {
            return true;
        }

        @Override
        public Object sessionAttribute(String name) {
            return attributes.get(name);
        }
    }
}
