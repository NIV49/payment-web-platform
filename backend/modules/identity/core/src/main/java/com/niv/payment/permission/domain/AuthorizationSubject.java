package com.niv.payment.permission.domain;

public record AuthorizationSubject(
    long userId,
    long membershipId,
    long tenantId,
    Long departmentId,
    long permissionVersion,
    long sessionVersion,
    long identityVersion,
    String issuer,
    String subject,
    boolean federated,
    boolean stepUpVerified
) {
    public AuthorizationSubject(long userId, long membershipId, long tenantId, Long departmentId,
                                long permissionVersion, long sessionVersion, boolean stepUpVerified) {
        this(userId, membershipId, tenantId, departmentId, permissionVersion, sessionVersion,
            0L, null, null, false, stepUpVerified);
    }

    public AuthorizationSubject {
        if (userId <= 0 || membershipId <= 0 || tenantId <= 0) {
            throw new IllegalArgumentException("User, membership and tenant identifiers must be positive");
        }
        if (permissionVersion < 0 || sessionVersion < 0 || identityVersion < 0) {
            throw new IllegalArgumentException("Versions cannot be negative");
        }
        if ((issuer == null) != (subject == null)) {
            throw new IllegalArgumentException("Issuer and subject must be present together");
        }
        if (federated && (issuer == null || issuer.isBlank() || subject.isBlank())) {
            throw new IllegalArgumentException("Federated identity mapping is required");
        }
    }
}
