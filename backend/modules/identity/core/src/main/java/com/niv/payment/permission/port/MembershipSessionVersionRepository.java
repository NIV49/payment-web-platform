package com.niv.payment.permission.port;

import java.util.Optional;

@FunctionalInterface
public interface MembershipSessionVersionRepository {
    Optional<MembershipVersions> findActiveVersions(long tenantId, long membershipId, long userId);

    record MembershipVersions(long permissionVersion, long sessionVersion) { }
}
