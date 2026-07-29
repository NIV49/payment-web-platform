package com.niv.payment.permission.port;

import com.niv.payment.permission.domain.GrantSnapshot;

import java.util.Optional;

public interface PermissionGrantCache {
    Optional<GrantSnapshot> find(long tenantId, long membershipId, long permissionVersion);

    void store(GrantSnapshot snapshot);
}
