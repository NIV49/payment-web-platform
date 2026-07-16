package com.niv.payment.permission.cache;

import com.niv.payment.permission.domain.GrantSnapshot;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class InMemoryPermissionGrantCache implements PermissionGrantCache {
    private final ConcurrentMap<PermissionCacheKey, GrantSnapshot> snapshots = new ConcurrentHashMap<>();

    @Override
    public Optional<GrantSnapshot> get(PermissionCacheKey key) {
        return Optional.ofNullable(snapshots.get(key));
    }

    @Override
    public void put(PermissionCacheKey key, GrantSnapshot snapshot) {
        snapshots.put(key, snapshot);
    }
}
