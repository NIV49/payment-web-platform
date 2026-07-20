package com.niv.payment.permission.application;

import com.niv.payment.permission.domain.AuthorizationSubject;
import com.niv.payment.permission.domain.GrantSnapshot;
import com.niv.payment.permission.port.MembershipVersionRepository;
import com.niv.payment.permission.port.PermissionGrantCache;
import com.niv.payment.permission.port.PermissionGrantRepository;

import java.time.Clock;
import java.util.Objects;

public final class CachedPermissionGrantLoader implements PermissionGrantLoader {
    private final MembershipVersionRepository versionRepository;
    private final PermissionGrantRepository grantRepository;
    private final PermissionGrantCache cache;
    private final Clock clock;

    public CachedPermissionGrantLoader(MembershipVersionRepository versionRepository,
                                       PermissionGrantRepository grantRepository,
                                       PermissionGrantCache cache) {
        this(versionRepository, grantRepository, cache, Clock.systemUTC());
    }

    public CachedPermissionGrantLoader(MembershipVersionRepository versionRepository,
                                       PermissionGrantRepository grantRepository,
                                       PermissionGrantCache cache,
                                       Clock clock) {
        this.versionRepository = Objects.requireNonNull(versionRepository, "versionRepository");
        this.grantRepository = Objects.requireNonNull(grantRepository, "grantRepository");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public GrantSnapshot load(AuthorizationSubject subject) {
        long currentVersion = versionRepository.findPermissionVersion(subject.tenantId(), subject.membershipId());
        return cache.find(subject.tenantId(), subject.membershipId(), currentVersion)
            .filter(snapshot -> snapshot.isFreshAt(clock.instant()))
            .orElseGet(() -> {
                GrantSnapshot snapshot = grantRepository.load(subject.tenantId(), subject.membershipId(), currentVersion);
                validate(snapshot, subject.tenantId(), subject.membershipId(), currentVersion);
                cache.store(snapshot);
                return snapshot;
            });
    }

    private static void validate(GrantSnapshot snapshot,
                                 long tenantId,
                                 long membershipId,
                                 long permissionVersion) {
        if (snapshot.tenantId() != tenantId
            || snapshot.membershipId() != membershipId
            || snapshot.permissionVersion() != permissionVersion) {
            throw new IllegalStateException("Permission repository returned a snapshot for the wrong cache key");
        }
    }
}
