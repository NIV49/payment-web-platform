package com.niv.payment.permission.support;

import com.niv.payment.permission.domain.GrantSnapshot;
import com.niv.payment.permission.port.PermissionGrantCache;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class InMemoryPermissionGrantCache implements PermissionGrantCache {
    private final ConcurrentMap<CacheIdentity, GrantSnapshot> snapshots = new ConcurrentHashMap<>();

    @Override
    public Optional<GrantSnapshot> find(long tenantId, long membershipId, long permissionVersion) {
        return Optional.ofNullable(snapshots.get(new CacheIdentity(tenantId, membershipId, permissionVersion)));
    }

    @Override
    public void store(GrantSnapshot snapshot) {
        snapshots.put(new CacheIdentity(snapshot.tenantId(), snapshot.membershipId(), snapshot.permissionVersion()),
            snapshot);
    }

    private record CacheIdentity(long tenantId, long membershipId, long permissionVersion) {
    }
}
