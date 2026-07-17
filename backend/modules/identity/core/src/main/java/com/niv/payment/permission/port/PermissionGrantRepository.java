package com.niv.payment.permission.port;

import com.niv.payment.permission.domain.GrantSnapshot;

@FunctionalInterface
public interface PermissionGrantRepository {
    GrantSnapshot load(long tenantId, long membershipId, long permissionVersion);
}
