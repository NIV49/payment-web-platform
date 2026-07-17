package com.niv.payment.permission.cache;

public record PermissionCacheKey(long tenantId, long membershipId, long permissionVersion) {
    public PermissionCacheKey {
        if (tenantId <= 0 || membershipId <= 0 || permissionVersion < 0) {
            throw new IllegalArgumentException("Cache key identity or version is invalid");
        }
    }

    public String redisKey() {
        return "iam:grant:%d:%d:v%d".formatted(tenantId, membershipId, permissionVersion);
    }
}
