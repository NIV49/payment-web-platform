package com.niv.payment.permission.port;

@FunctionalInterface
public interface MembershipSessionVersionRepository {
    long findSessionVersion(long tenantId, long membershipId);
}
