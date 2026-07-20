package com.niv.payment.permission.domain;

import java.time.Instant;
import java.util.List;

public record GrantSnapshot(
    long membershipId,
    long tenantId,
    long permissionVersion,
    List<PermissionGrant> grants,
    Instant refreshAfter
) {
    public GrantSnapshot {
        if (membershipId <= 0 || tenantId <= 0 || permissionVersion < 0) {
            throw new IllegalArgumentException("Snapshot identity or version is invalid");
        }
        grants = grants == null ? List.of() : List.copyOf(grants);
    }

    public GrantSnapshot(long membershipId,
                         long tenantId,
                         long permissionVersion,
                         List<PermissionGrant> grants) {
        this(membershipId, tenantId, permissionVersion, grants, null);
    }

    public boolean isFreshAt(Instant instant) {
        return refreshAfter == null || instant.isBefore(refreshAfter);
    }
}
