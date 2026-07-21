package com.niv.payment.permission.application;

import com.niv.payment.permission.domain.AuthorizationSubject;
import com.niv.payment.permission.domain.GrantSnapshot;
import com.niv.payment.permission.port.MembershipVersionRepository;
import com.niv.payment.permission.port.PermissionGrantCache;
import com.niv.payment.permission.port.PermissionGrantRepository;
import com.niv.payment.permission.port.StalePermissionVersionException;

import java.util.Objects;

public final class CachedPermissionGrantLoader implements PermissionGrantLoader {
    private static final int MAX_PERMISSION_VERSION_ATTEMPTS = 2;

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
        for (int attempt = 0; attempt < MAX_PERMISSION_VERSION_ATTEMPTS; attempt++) {
            GrantSnapshot cached = cache.find(subject.tenantId(), subject.membershipId(), currentVersion)
                .filter(CachedPermissionGrantLoader::isCacheable)
                .orElse(null);
            if (cached != null) {
                // This final version read is the linearization point for a cache hit. A revocation
                // committed before it must invalidate the prepared snapshot; a commit after it
                // belongs to an already-in-flight request. Admin writes additionally recheck the
                // actor and versions after acquiring their transaction locks.
                long confirmedVersion = versionRepository.findPermissionVersion(
                    subject.tenantId(), subject.membershipId());
                if (confirmedVersion == currentVersion) {
                    return cached;
                }
                if (attempt + 1 >= MAX_PERMISSION_VERSION_ATTEMPTS) {
                    throw new StalePermissionVersionException();
                }
                currentVersion = confirmedVersion;
                continue;
            }
            try {
                return loadFreshFromSource(subject, currentVersion);
            } catch (StalePermissionVersionException staleVersion) {
                if (attempt + 1 >= MAX_PERMISSION_VERSION_ATTEMPTS) {
                    throw staleVersion;
                }
                currentVersion = versionRepository.findPermissionVersion(
                    subject.tenantId(), subject.membershipId());
            }
        }
        throw new IllegalStateException("Permission version retry loop exited unexpectedly");
    }

    private GrantSnapshot loadFreshFromSource(AuthorizationSubject subject, long currentVersion) {
        GrantSnapshot snapshot = grantRepository.load(
            subject.tenantId(), subject.membershipId(), currentVersion);
        validate(snapshot, subject.tenantId(), subject.membershipId(), currentVersion);
        if (isCacheable(snapshot)) {
            cache.store(snapshot);
        }
        return snapshot;
    }

    private static boolean isCacheable(GrantSnapshot snapshot) {
        // A temporal boundary is evaluated by PostgreSQL statement_timestamp(). Comparing that
        // absolute timestamp with an application-node clock could extend an expired grant.
        return snapshot.refreshAfter() == null;
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
