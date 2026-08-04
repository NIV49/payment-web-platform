package com.niv.payment.permission;

import com.niv.payment.permission.domain.AccountDomain;
import com.niv.payment.permission.security.SaTokenFacade;
import com.niv.payment.permission.security.SaTokenSessionBridge;
import com.niv.payment.permission.security.SessionAttributeNames;
import com.niv.payment.permission.security.InvalidSessionException;
import com.niv.payment.permission.port.MembershipSessionVersionRepository.MembershipVersions;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SaTokenSessionBridgeTest {

    @Test
    void mapsOnlyTrustedServerSessionAttributesToAuthorizationSubject() {
        Map<String, Object> attributes = Map.of(
            SessionAttributeNames.USER_ID, 10L,
            SessionAttributeNames.ACCOUNT_DOMAIN, "PLATFORM",
            SessionAttributeNames.MEMBERSHIP_ID, 20L,
            SessionAttributeNames.TENANT_ID, 30L,
            SessionAttributeNames.DEPARTMENT_ID, 40L,
            SessionAttributeNames.PERMISSION_VERSION, 7L,
            SessionAttributeNames.SESSION_VERSION, 3L,
            SessionAttributeNames.STEP_UP_VERIFIED, true);
        SaTokenFacade facade = new FakeSaTokenFacade(attributes);

        var subject = new SaTokenSessionBridge(AccountDomain.PLATFORM, facade,
            (domain, tenantId, membershipId, userId) -> Optional.of(new MembershipVersions(7L, 3L)))
            .currentSubject();

        assertEquals(20L, subject.membershipId());
        assertEquals(30L, subject.tenantId());
        assertEquals(7L, subject.permissionVersion());
    }

    @Test
    void rejectsARevokedSessionVersion() {
        Map<String, Object> attributes = Map.of(
            SessionAttributeNames.USER_ID, 10L,
            SessionAttributeNames.ACCOUNT_DOMAIN, "PLATFORM",
            SessionAttributeNames.MEMBERSHIP_ID, 20L,
            SessionAttributeNames.TENANT_ID, 30L,
            SessionAttributeNames.PERMISSION_VERSION, 7L,
            SessionAttributeNames.SESSION_VERSION, 3L,
            SessionAttributeNames.STEP_UP_VERIFIED, false);

        var bridge = new SaTokenSessionBridge(AccountDomain.PLATFORM, new FakeSaTokenFacade(attributes),
            (domain, tenantId, membershipId, userId) -> Optional.of(new MembershipVersions(7L, 4L)));

        org.junit.jupiter.api.Assertions.assertThrows(InvalidSessionException.class, bridge::currentSubject);
    }

    @Test
    void rejectsARevokedPermissionVersion() {
        Map<String, Object> attributes = Map.of(
            SessionAttributeNames.USER_ID, 10L,
            SessionAttributeNames.ACCOUNT_DOMAIN, "PLATFORM",
            SessionAttributeNames.MEMBERSHIP_ID, 20L,
            SessionAttributeNames.TENANT_ID, 30L,
            SessionAttributeNames.PERMISSION_VERSION, 7L,
            SessionAttributeNames.SESSION_VERSION, 3L,
            SessionAttributeNames.STEP_UP_VERIFIED, false);

        var bridge = new SaTokenSessionBridge(AccountDomain.PLATFORM, new FakeSaTokenFacade(attributes),
            (domain, tenantId, membershipId, userId) -> Optional.of(new MembershipVersions(8L, 3L)));

        org.junit.jupiter.api.Assertions.assertThrows(InvalidSessionException.class, bridge::currentSubject);
    }

    @Test
    void rejectsASessionWhoseMembershipIsNoLongerActive() {
        Map<String, Object> attributes = Map.of(
            SessionAttributeNames.USER_ID, 10L,
            SessionAttributeNames.ACCOUNT_DOMAIN, "PLATFORM",
            SessionAttributeNames.MEMBERSHIP_ID, 20L,
            SessionAttributeNames.TENANT_ID, 30L,
            SessionAttributeNames.PERMISSION_VERSION, 7L,
            SessionAttributeNames.SESSION_VERSION, 3L,
            SessionAttributeNames.STEP_UP_VERIFIED, false);

        var bridge = new SaTokenSessionBridge(AccountDomain.PLATFORM, new FakeSaTokenFacade(attributes),
            (domain, tenantId, membershipId, userId) -> Optional.empty());

        org.junit.jupiter.api.Assertions.assertThrows(InvalidSessionException.class, bridge::currentSubject);
    }

    @Test
    void rejectsASessionWhoseUserDoesNotOwnTheActiveMembership() {
        Map<String, Object> attributes = Map.of(
            SessionAttributeNames.USER_ID, 10L,
            SessionAttributeNames.ACCOUNT_DOMAIN, "PLATFORM",
            SessionAttributeNames.MEMBERSHIP_ID, 20L,
            SessionAttributeNames.TENANT_ID, 30L,
            SessionAttributeNames.PERMISSION_VERSION, 7L,
            SessionAttributeNames.SESSION_VERSION, 3L,
            SessionAttributeNames.STEP_UP_VERIFIED, false);

        var bridge = new SaTokenSessionBridge(AccountDomain.PLATFORM, new FakeSaTokenFacade(attributes),
            (domain, tenantId, membershipId, userId) -> userId == 11L
                ? Optional.of(new MembershipVersions(7L, 3L))
                : Optional.empty());

        org.junit.jupiter.api.Assertions.assertThrows(InvalidSessionException.class, bridge::currentSubject);
    }

    @Test
    void rejectsCrossRealmAndMissingDomainSessions() {
        Map<String, Object> merchantAttributes = Map.of(
            SessionAttributeNames.USER_ID, 10L,
            SessionAttributeNames.ACCOUNT_DOMAIN, "MERCHANT",
            SessionAttributeNames.MEMBERSHIP_ID, 20L,
            SessionAttributeNames.TENANT_ID, 30L,
            SessionAttributeNames.PERMISSION_VERSION, 7L,
            SessionAttributeNames.SESSION_VERSION, 3L,
            SessionAttributeNames.STEP_UP_VERIFIED, false);
        var repository = (com.niv.payment.permission.port.MembershipSessionVersionRepository)
            (domain, tenantId, membershipId, userId) -> Optional.of(new MembershipVersions(7L, 3L));

        var crossRealm = new SaTokenSessionBridge(
            AccountDomain.PLATFORM, new FakeSaTokenFacade(merchantAttributes), repository);
        org.junit.jupiter.api.Assertions.assertThrows(InvalidSessionException.class, crossRealm::currentSubject);

        var missingDomain = new java.util.HashMap<>(merchantAttributes);
        missingDomain.remove(SessionAttributeNames.ACCOUNT_DOMAIN);
        var missing = new SaTokenSessionBridge(
            AccountDomain.PLATFORM, new FakeSaTokenFacade(missingDomain), repository);
        org.junit.jupiter.api.Assertions.assertThrows(InvalidSessionException.class, missing::currentSubject);
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
