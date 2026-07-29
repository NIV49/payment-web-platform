package com.niv.payment.permission.port;

import java.util.OptionalLong;

@FunctionalInterface
public interface MembershipSessionVersionRepository {
    OptionalLong findActiveSessionVersion(long tenantId, long membershipId, long userId);
}
