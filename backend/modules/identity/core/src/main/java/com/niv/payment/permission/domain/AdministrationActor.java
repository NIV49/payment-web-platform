package com.niv.payment.permission.domain;

/** Trusted operator identity and session/authorization versions captured by the HTTP policy-enforcement point. */
public record AdministrationActor(
    long membershipId,
    long expectedUserId,
    long expectedPermissionVersion,
    long expectedSessionVersion
) {
    public AdministrationActor {
        if (membershipId <= 0 || expectedUserId <= 0
            || expectedPermissionVersion < 0 || expectedSessionVersion < 0) {
            throw new IllegalArgumentException("Invalid administration actor");
        }
    }
}
