package com.niv.payment.merchant.core;

public record MerchantActor(
    long userId,
    long membershipId,
    long tenantId,
    AccountDomain accountDomain,
    long permissionVersion,
    long sessionVersion,
    long identityVersion,
    String issuer,
    String subject,
    boolean federated,
    boolean stepUpVerified
) {
    public MerchantActor {
        if (userId <= 0 || membershipId <= 0 || tenantId <= 0 || accountDomain == null
            || permissionVersion < 0 || sessionVersion < 0 || identityVersion < 0) {
            throw new MerchantException.InvalidRequest("Invalid merchant actor");
        }
        if ((issuer == null) != (subject == null) || (federated && (issuer == null
            || issuer.isBlank() || subject.isBlank()))) {
            throw new MerchantException.InvalidRequest("Invalid merchant identity mapping");
        }
    }

    public static MerchantActor local(long userId, long membershipId, long tenantId,
                                      AccountDomain accountDomain, long permissionVersion,
                                      long sessionVersion, long identityVersion) {
        return new MerchantActor(userId, membershipId, tenantId, accountDomain,
            permissionVersion, sessionVersion, identityVersion, null, null, false, false);
    }

    public static MerchantActor localStepUp(long userId, long membershipId, long tenantId,
                                            AccountDomain accountDomain, long permissionVersion,
                                            long sessionVersion, long identityVersion) {
        return new MerchantActor(userId, membershipId, tenantId, accountDomain,
            permissionVersion, sessionVersion, identityVersion, null, null, false, true);
    }
}
