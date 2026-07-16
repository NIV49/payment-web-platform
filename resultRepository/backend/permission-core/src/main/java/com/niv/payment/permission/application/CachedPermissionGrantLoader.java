package com.niv.payment.permission.application;

import com.niv.payment.permission.cache.PermissionCacheKey;
import com.niv.payment.permission.cache.PermissionGrantCache;
import com.niv.payment.permission.domain.AuthorizationSubject;
import com.niv.payment.permission.domain.GrantSnapshot;
import com.niv.payment.permission.port.MembershipVersionRepository;
import com.niv.payment.permission.port.PermissionGrantRepository;

import java.util.Objects;

public final class CachedPermissionGrantLoader implements PermissionGrantLoader {
    private final MembershipVersionRepository versionRepository;
    private final PermissionGrantRepository grantRepository;
    private final PermissionGrantCache cache;

    public CachedPermissionGrantLoader(MembershipVersionRepository versionRepository,
                                       PermissionGrantRepository grantRepository,
                                       PermissionGrantCache cache) {
        this.versionRepository = Objects.requireNonNull(versionRepository, "versionRepository");
        this.grantRepository = Objects.requireNonNull(grantRepository, "grantRepository");
        this.cache = Objects.requireNonNull(cache, "cache");
    }

    @Override
    public GrantSnapshot load(AuthorizationSubject subject) {
        long currentVersion = versionRepository.findPermissionVersion(subject.tenantId(), subject.membershipId());
        PermissionCacheKey key = new PermissionCacheKey(subject.tenantId(), subject.membershipId(), currentVersion);
        return cache.get(key).orElseGet(() -> {
            GrantSnapshot snapshot = grantRepository.load(subject.tenantId(), subject.membershipId(), currentVersion);
            validate(snapshot, key);
            cache.put(key, snapshot);
            return snapshot;
        });
    }

    private static void validate(GrantSnapshot snapshot, PermissionCacheKey key) {
        if (snapshot.tenantId() != key.tenantId()
            || snapshot.membershipId() != key.membershipId()
            || snapshot.permissionVersion() != key.permissionVersion()) {
            throw new IllegalStateException("Permission repository returned a snapshot for the wrong cache key");
        }
    }
}
