package com.niv.payment.permission.domain;

public record AuthorizationSubject(
    long userId,
    long membershipId,
    long tenantId,
    Long departmentId,
    long permissionVersion,
    long sessionVersion,
    boolean stepUpVerified
) {
    public AuthorizationSubject {
        if (userId <= 0 || membershipId <= 0 || tenantId <= 0) {
            throw new IllegalArgumentException("User, membership and tenant identifiers must be positive");
        }
        if (permissionVersion < 0 || sessionVersion < 0) {
            throw new IllegalArgumentException("Versions cannot be negative");
        }
    }
}
