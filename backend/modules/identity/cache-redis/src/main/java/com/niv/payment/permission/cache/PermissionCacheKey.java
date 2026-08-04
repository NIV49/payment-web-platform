package com.niv.payment.permission.cache;

import com.niv.payment.permission.domain.AccountDomain;

record PermissionCacheKey(AccountDomain accountDomain, long tenantId, long membershipId,
                          long permissionVersion) {
    public PermissionCacheKey {
        java.util.Objects.requireNonNull(accountDomain, "accountDomain");
        if (tenantId <= 0 || membershipId <= 0 || permissionVersion < 0) {
            throw new IllegalArgumentException("Cache key identity or version is invalid");
        }
    }

    public String redisKey() {
        return "iam:%s:grant:%d:%d:v%d".formatted(
            accountDomain.cacheNamespace(), tenantId, membershipId, permissionVersion);
    }
}
