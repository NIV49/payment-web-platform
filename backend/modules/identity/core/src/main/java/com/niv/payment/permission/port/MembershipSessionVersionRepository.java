package com.niv.payment.permission.port;

import com.niv.payment.permission.domain.AccountDomain;

import java.util.Optional;

@FunctionalInterface
public interface MembershipSessionVersionRepository {
    Optional<MembershipVersions> findActiveVersions(AccountDomain accountDomain, long tenantId,
                                                     long membershipId, long userId);

    record MembershipVersions(long permissionVersion, long sessionVersion, long identityVersion,
                              String issuer, String subject, boolean localLoginCapable) {
        public MembershipVersions {
            if (permissionVersion < 0 || sessionVersion < 0 || identityVersion < 0) {
                throw new IllegalArgumentException("Session versions must be non-negative");
            }
            if ((issuer == null) != (subject == null)) {
                throw new IllegalArgumentException("Issuer and subject must either both be present or both be absent");
            }
        }
    }
}
