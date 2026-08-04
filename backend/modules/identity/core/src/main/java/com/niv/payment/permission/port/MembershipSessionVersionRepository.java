package com.niv.payment.permission.port;

import com.niv.payment.permission.domain.AccountDomain;

import java.util.Optional;

@FunctionalInterface
public interface MembershipSessionVersionRepository {
    Optional<MembershipVersions> findActiveVersions(AccountDomain accountDomain, long tenantId,
                                                     long membershipId, long userId);

    record MembershipVersions(long permissionVersion, long sessionVersion) { }
}
