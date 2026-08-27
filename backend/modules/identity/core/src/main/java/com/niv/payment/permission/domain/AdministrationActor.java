package com.niv.payment.permission.domain;

/** Trusted operator identity and session/authorization versions captured by the HTTP policy-enforcement point. */
public record AdministrationActor(
    long membershipId,
    long expectedUserId,
    long expectedPermissionVersion,
    long expectedSessionVersion,
    long expectedIdentityVersion,
    String expectedIssuer,
    String expectedSubject,
    boolean federated
) {
    public AdministrationActor(long membershipId, long expectedUserId,
                               long expectedPermissionVersion, long expectedSessionVersion) {
        this(membershipId, expectedUserId, expectedPermissionVersion, expectedSessionVersion,
            0L, null, null, false);
    }

    public AdministrationActor {
        if (membershipId <= 0 || expectedUserId <= 0
            || expectedPermissionVersion < 0 || expectedSessionVersion < 0
            || expectedIdentityVersion < 0) {
            throw new IllegalArgumentException("Invalid administration actor");
        }
        if ((expectedIssuer == null) != (expectedSubject == null)) {
            throw new IllegalArgumentException("Issuer and subject must be present together");
        }
        if (federated && (expectedIssuer == null || expectedIssuer.isBlank()
            || expectedSubject.isBlank())) {
            throw new IllegalArgumentException("Federated identity mapping is required");
        }
    }

    public static AdministrationActor from(AuthorizationSubject subject) {
        return new AdministrationActor(
            subject.membershipId(), subject.userId(), subject.permissionVersion(),
            subject.sessionVersion(), subject.identityVersion(), subject.issuer(),
            subject.subject(), subject.federated());
    }
}
