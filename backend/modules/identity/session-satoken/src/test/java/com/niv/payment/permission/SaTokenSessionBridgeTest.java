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
            SessionAttributeNames.IDENTITY_VERSION, 11L,
            SessionAttributeNames.STEP_UP_VERIFIED, true);
        SaTokenFacade facade = new FakeSaTokenFacade(attributes);

        var subject = new SaTokenSessionBridge(AccountDomain.PLATFORM, facade,
            (domain, tenantId, membershipId, userId) -> Optional.of(
                new MembershipVersions(7L, 3L, 11L, "local", "local-user-10", true)))
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
            SessionAttributeNames.IDENTITY_VERSION, 11L,
            SessionAttributeNames.STEP_UP_VERIFIED, false);

        var bridge = new SaTokenSessionBridge(AccountDomain.PLATFORM, new FakeSaTokenFacade(attributes),
            (domain, tenantId, membershipId, userId) -> Optional.of(
                new MembershipVersions(7L, 4L, 11L, "local", "local-user-10", true)));

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
            SessionAttributeNames.IDENTITY_VERSION, 11L,
            SessionAttributeNames.STEP_UP_VERIFIED, false);

        var bridge = new SaTokenSessionBridge(AccountDomain.PLATFORM, new FakeSaTokenFacade(attributes),
            (domain, tenantId, membershipId, userId) -> Optional.of(
                new MembershipVersions(8L, 3L, 11L, "local", "local-user-10", true)));

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
            SessionAttributeNames.IDENTITY_VERSION, 11L,
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
            SessionAttributeNames.IDENTITY_VERSION, 11L,
            SessionAttributeNames.STEP_UP_VERIFIED, false);

        var bridge = new SaTokenSessionBridge(AccountDomain.PLATFORM, new FakeSaTokenFacade(attributes),
            (domain, tenantId, membershipId, userId) -> userId == 11L
                ? Optional.of(new MembershipVersions(7L, 3L, 11L, "local", "local-user-10", true))
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
            SessionAttributeNames.IDENTITY_VERSION, 11L,
            SessionAttributeNames.STEP_UP_VERIFIED, false);
        var repository = (com.niv.payment.permission.port.MembershipSessionVersionRepository)
            (domain, tenantId, membershipId, userId) -> Optional.of(
                new MembershipVersions(7L, 3L, 11L, "local", "local-user-10", true));

        var crossRealm = new SaTokenSessionBridge(
            AccountDomain.PLATFORM, new FakeSaTokenFacade(merchantAttributes), repository);
        org.junit.jupiter.api.Assertions.assertThrows(InvalidSessionException.class, crossRealm::currentSubject);

        var missingDomain = new java.util.HashMap<>(merchantAttributes);
        missingDomain.remove(SessionAttributeNames.ACCOUNT_DOMAIN);
        var missing = new SaTokenSessionBridge(
            AccountDomain.PLATFORM, new FakeSaTokenFacade(missingDomain), repository);
        org.junit.jupiter.api.Assertions.assertThrows(InvalidSessionException.class, missing::currentSubject);
    }

    @Test
    void rejectsARevokedIdentityVersion() {
        Map<String, Object> attributes = baseAttributes();
        var bridge = new SaTokenSessionBridge(AccountDomain.PLATFORM, new FakeSaTokenFacade(attributes),
            (domain, tenantId, membershipId, userId) -> Optional.of(
                new MembershipVersions(7L, 3L, 12L, "local", "local-user-10", true)));

        org.junit.jupiter.api.Assertions.assertThrows(InvalidSessionException.class, bridge::currentSubject);
    }

    @Test
    void localSessionRequiresALoginCapableLocalCredential() {
        var bridge = new SaTokenSessionBridge(AccountDomain.PLATFORM,
            new FakeSaTokenFacade(baseAttributes()),
            (domain, tenantId, membershipId, userId) -> Optional.of(
                new MembershipVersions(7L, 3L, 11L, "local", "local-user-10", false)));

        org.junit.jupiter.api.Assertions.assertThrows(InvalidSessionException.class, bridge::currentSubject);
    }

    @Test
    void federatedSessionRequiresExactIssuerSubjectAndEntryHost() {
        var attributes = new java.util.HashMap<>(baseAttributes());
        attributes.put(SessionAttributeNames.ISSUER, "https://idp.example.test/realms/platform");
        attributes.put(SessionAttributeNames.SUBJECT, "subject-10");
        attributes.put(SessionAttributeNames.ENTRY_HOST, "ops.example.test");
        var bridge = new SaTokenSessionBridge(AccountDomain.PLATFORM, new FakeSaTokenFacade(attributes),
            (domain, tenantId, membershipId, userId) -> Optional.of(new MembershipVersions(
                7L, 3L, 11L, "https://idp.example.test/realms/platform", "subject-10", false)));

        assertEquals(20L, bridge.currentSubject("OPS.EXAMPLE.TEST").membershipId());
        org.junit.jupiter.api.Assertions.assertThrows(InvalidSessionException.class,
            () -> bridge.currentSubject("other.example.test"));

        attributes.put(SessionAttributeNames.SUBJECT, "rebound-subject");
        org.junit.jupiter.api.Assertions.assertThrows(InvalidSessionException.class,
            () -> bridge.currentSubject("ops.example.test"));
    }

    @Test
    void requestProofIsIndependentFromTheSessionCookieAndComparedExactly() {
        var attributes = new java.util.HashMap<>(baseAttributes());
        attributes.put(SessionAttributeNames.REQUEST_PROOF, "synchronizer-proof");
        var bridge = new SaTokenSessionBridge(AccountDomain.PLATFORM, new FakeSaTokenFacade(attributes),
            (domain, tenantId, membershipId, userId) -> Optional.of(
                new MembershipVersions(7L, 3L, 11L, "local", "local-user-10", true)));

        assertEquals("synchronizer-proof", bridge.requestProof());
        bridge.requireRequestProof("synchronizer-proof");
        org.junit.jupiter.api.Assertions.assertThrows(InvalidSessionException.class,
            () -> bridge.requireRequestProof("session-cookie-value"));
    }

    private static Map<String, Object> baseAttributes() {
        return Map.of(
            SessionAttributeNames.USER_ID, 10L,
            SessionAttributeNames.ACCOUNT_DOMAIN, "PLATFORM",
            SessionAttributeNames.MEMBERSHIP_ID, 20L,
            SessionAttributeNames.TENANT_ID, 30L,
            SessionAttributeNames.PERMISSION_VERSION, 7L,
            SessionAttributeNames.SESSION_VERSION, 3L,
            SessionAttributeNames.IDENTITY_VERSION, 11L,
            SessionAttributeNames.STEP_UP_VERIFIED, false);
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
