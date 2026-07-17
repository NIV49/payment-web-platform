package com.niv.payment.permission.security;

import com.niv.payment.permission.domain.AuthorizationSubject;
import com.niv.payment.permission.port.MembershipSessionVersionRepository;

import java.util.Objects;

public final class SaTokenSessionBridge {
    private final SaTokenFacade saToken;
    private final MembershipSessionVersionRepository sessionVersionRepository;

    public SaTokenSessionBridge(SaTokenFacade saToken,
                                MembershipSessionVersionRepository sessionVersionRepository) {
        this.saToken = Objects.requireNonNull(saToken, "saToken");
        this.sessionVersionRepository = Objects.requireNonNull(sessionVersionRepository, "sessionVersionRepository");
    }

    public AuthorizationSubject currentSubject() {
        if (!saToken.isLoggedIn()) {
            throw new IllegalStateException("Authentication is required");
        }
        long membershipId = requiredLong(SessionAttributeNames.MEMBERSHIP_ID);
        long tenantId = requiredLong(SessionAttributeNames.TENANT_ID);
        long sessionVersion = requiredLong(SessionAttributeNames.SESSION_VERSION);
        long currentSessionVersion = sessionVersionRepository.findSessionVersion(tenantId, membershipId);
        if (sessionVersion != currentSessionVersion) {
            throw new IllegalStateException("Session version is stale");
        }
        return new AuthorizationSubject(
            requiredLong(SessionAttributeNames.USER_ID),
            membershipId,
            tenantId,
            optionalLong(SessionAttributeNames.DEPARTMENT_ID),
            requiredLong(SessionAttributeNames.PERMISSION_VERSION),
            sessionVersion,
            requiredBoolean(SessionAttributeNames.STEP_UP_VERIFIED));
    }

    private Long optionalLong(String name) {
        Object value = saToken.sessionAttribute(name);
        if (value == null) {
            return null;
        }
        if (!(value instanceof Number number)) {
            throw new IllegalStateException("Invalid trusted numeric session attribute: " + name);
        }
        return number.longValue();
    }

    private long requiredLong(String name) {
        Object value = saToken.sessionAttribute(name);
        if (!(value instanceof Number number)) {
            throw new IllegalStateException("Missing trusted numeric session attribute: " + name);
        }
        return number.longValue();
    }

    private boolean requiredBoolean(String name) {
        Object value = saToken.sessionAttribute(name);
        if (!(value instanceof Boolean booleanValue)) {
            throw new IllegalStateException("Missing trusted boolean session attribute: " + name);
        }
        return booleanValue;
    }
}
