package com.niv.payment.permission.domain;

import java.time.Instant;
import java.util.List;

/**
 * A permission snapshot evaluated by the grant repository.
 *
 * <p>{@code refreshAfter} records a temporal boundary observed by PostgreSQL. A non-null value is
 * a cache-exclusion marker, not an application-clock deadline: callers must reload from the
 * repository and must never compare it with an application-node clock.</p>
 */
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
}
