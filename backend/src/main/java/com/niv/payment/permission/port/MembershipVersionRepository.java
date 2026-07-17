package com.niv.payment.permission.port;

@FunctionalInterface
public interface MembershipVersionRepository {
    long findPermissionVersion(long tenantId, long membershipId);
}
