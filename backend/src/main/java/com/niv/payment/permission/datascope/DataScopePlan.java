package com.niv.payment.permission.datascope;

import com.niv.payment.permission.domain.PermissionCode;

import java.util.List;
import java.util.Objects;

public record DataScopePlan(
    long tenantId,
    long membershipId,
    PermissionCode permission,
    long permissionVersion,
    List<GrantPredicate> grantPredicates
) {
    public DataScopePlan {
        if (tenantId <= 0 || membershipId <= 0 || permissionVersion < 0) {
            throw new IllegalArgumentException("Plan identity or version is invalid");
        }
        Objects.requireNonNull(permission, "permission");
        grantPredicates = grantPredicates == null ? List.of() : List.copyOf(grantPredicates);
    }
}
