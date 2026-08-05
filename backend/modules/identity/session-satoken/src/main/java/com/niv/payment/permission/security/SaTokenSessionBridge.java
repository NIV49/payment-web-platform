package com.niv.payment.permission.security;

import com.niv.payment.permission.domain.AuthorizationSubject;
import com.niv.payment.permission.domain.AccountDomain;
import com.niv.payment.permission.port.MembershipSessionVersionRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Objects;

public final class SaTokenSessionBridge {
    private final SaTokenFacade saToken;
    private final MembershipSessionVersionRepository sessionVersionRepository;
    private final AccountDomain accountDomain;

    public SaTokenSessionBridge(AccountDomain accountDomain, SaTokenFacade saToken,
                                MembershipSessionVersionRepository sessionVersionRepository) {
        this.accountDomain = Objects.requireNonNull(accountDomain, "accountDomain");
        this.saToken = Objects.requireNonNull(saToken, "saToken");
        this.sessionVersionRepository = Objects.requireNonNull(sessionVersionRepository, "sessionVersionRepository");
    }

    public AuthorizationSubject currentSubject() {
        return currentSubject(null);
    }

    public AuthorizationSubject currentSubject(String requestHost) {
        if (!saToken.isLoggedIn()) {
            throw new InvalidSessionException("Authentication is required");
        }
        AccountDomain sessionDomain = requiredAccountDomain();
        if (sessionDomain != accountDomain) {
            throw new InvalidSessionException("Session realm does not match this backoffice");
        }
        long userId = requiredLong(SessionAttributeNames.USER_ID);
        long membershipId = requiredLong(SessionAttributeNames.MEMBERSHIP_ID);
        long tenantId = requiredLong(SessionAttributeNames.TENANT_ID);
        long permissionVersion = requiredLong(SessionAttributeNames.PERMISSION_VERSION);
        long sessionVersion = requiredLong(SessionAttributeNames.SESSION_VERSION);
        long identityVersion = requiredLong(SessionAttributeNames.IDENTITY_VERSION);
        var currentVersions = sessionVersionRepository.findActiveVersions(
                accountDomain, tenantId, membershipId, userId)
            .orElseThrow(() -> new InvalidSessionException(
                "No active tenant, user, credential, and membership tuple found for session validation"));
        if (permissionVersion != currentVersions.permissionVersion()) {
            throw new InvalidSessionException("Permission version is stale");
        }
        if (sessionVersion != currentVersions.sessionVersion()) {
            throw new InvalidSessionException("Session version is stale");
        }
        if (identityVersion != currentVersions.identityVersion()) {
            throw new InvalidSessionException("Identity version is stale");
        }
        validateFederatedIdentity(currentVersions, requestHost);
        return new AuthorizationSubject(
            userId,
            membershipId,
            tenantId,
            optionalLong(SessionAttributeNames.DEPARTMENT_ID),
            permissionVersion,
            sessionVersion,
            requiredBoolean(SessionAttributeNames.STEP_UP_VERIFIED));
    }

    public String requestProof() {
        currentSubject();
        return requiredString(SessionAttributeNames.REQUEST_PROOF);
    }

    public void requireRequestProof(String presented) {
        currentSubject();
        String expected = requiredString(SessionAttributeNames.REQUEST_PROOF);
        if (presented == null || presented.length() > 256
            || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8))) {
            throw new InvalidSessionException("Request proof is invalid");
        }
    }

    private void validateFederatedIdentity(MembershipSessionVersionRepository.MembershipVersions current,
                                           String requestHost) {
        Object sessionIssuer = saToken.sessionAttribute(SessionAttributeNames.ISSUER);
        Object sessionSubject = saToken.sessionAttribute(SessionAttributeNames.SUBJECT);
        Object entryHost = saToken.sessionAttribute(SessionAttributeNames.ENTRY_HOST);
        boolean federated = sessionIssuer != null || sessionSubject != null || entryHost != null;
        if (!federated) {
            if (!current.localLoginCapable()) {
                throw new InvalidSessionException("Local credential is no longer login capable");
            }
            return;
        }
        if (!(sessionIssuer instanceof String issuer) || !(sessionSubject instanceof String subject)
            || !(entryHost instanceof String host)
            || !issuer.equals(current.issuer()) || !subject.equals(current.subject())) {
            throw new InvalidSessionException("Federated identity mapping is stale");
        }
        if (requestHost != null && !host.equals(requestHost.trim().toLowerCase(Locale.ROOT))) {
            throw new InvalidSessionException("Session entry host does not match the request host");
        }
    }

    private AccountDomain requiredAccountDomain() {
        Object value = saToken.sessionAttribute(SessionAttributeNames.ACCOUNT_DOMAIN);
        if (!(value instanceof String name)) {
            throw new InvalidSessionException("Missing trusted account domain session attribute");
        }
        try {
            return AccountDomain.valueOf(name);
        } catch (IllegalArgumentException exception) {
            throw new InvalidSessionException("Invalid trusted account domain session attribute");
        }
    }

    private Long optionalLong(String name) {
        Object value = saToken.sessionAttribute(name);
        if (value == null) {
            return null;
        }
        if (!(value instanceof Number number)) {
            throw new InvalidSessionException("Invalid trusted numeric session attribute: " + name);
        }
        return number.longValue();
    }

    private long requiredLong(String name) {
        Object value = saToken.sessionAttribute(name);
        if (!(value instanceof Number number)) {
            throw new InvalidSessionException("Missing trusted numeric session attribute: " + name);
        }
        return number.longValue();
    }

    private boolean requiredBoolean(String name) {
        Object value = saToken.sessionAttribute(name);
        if (!(value instanceof Boolean booleanValue)) {
            throw new InvalidSessionException("Missing trusted boolean session attribute: " + name);
        }
        return booleanValue;
    }

    private String requiredString(String name) {
        Object value = saToken.sessionAttribute(name);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new InvalidSessionException("Missing trusted string session attribute: " + name);
        }
        return text;
    }
}
