package com.niv.payment.permission.port;

import java.util.OptionalLong;

@FunctionalInterface
public interface MembershipSessionVersionRepository {
    OptionalLong findSessionVersion(long tenantId, long membershipId);
}
