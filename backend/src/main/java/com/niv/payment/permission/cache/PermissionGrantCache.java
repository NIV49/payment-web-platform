package com.niv.payment.permission.cache;

import com.niv.payment.permission.domain.GrantSnapshot;

import java.util.Optional;

public interface PermissionGrantCache {
    Optional<GrantSnapshot> get(PermissionCacheKey key);

    void put(PermissionCacheKey key, GrantSnapshot snapshot);
}
