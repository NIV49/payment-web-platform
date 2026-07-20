package com.niv.payment.permission;

import com.niv.payment.permission.application.CachedPermissionGrantLoader;
import com.niv.payment.permission.domain.AuthorizationSubject;
import com.niv.payment.permission.domain.GrantSnapshot;
import com.niv.payment.permission.port.MembershipVersionRepository;
import com.niv.payment.permission.port.PermissionGrantRepository;
import com.niv.payment.permission.support.InMemoryPermissionGrantCache;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CachedPermissionGrantLoaderTest {

    @Test
    void permissionVersionChangeForcesANewCacheEntry() {
        AtomicLong version = new AtomicLong(1L);
        AtomicInteger repositoryLoads = new AtomicInteger();
        MembershipVersionRepository versionRepository = (tenantId, membershipId) -> version.get();
        PermissionGrantRepository grantRepository = (tenantId, membershipId, permissionVersion) -> {
            repositoryLoads.incrementAndGet();
            return new GrantSnapshot(membershipId, tenantId, permissionVersion, List.of());
        };
        var loader = new CachedPermissionGrantLoader(versionRepository, grantRepository,
            new InMemoryPermissionGrantCache());
        AuthorizationSubject subject = new AuthorizationSubject(10L, 20L, 30L, 40L, 1L, 1L, false);

        loader.load(subject);
        loader.load(subject);
        version.incrementAndGet();
        loader.load(subject);

        assertEquals(2, repositoryLoads.get());
    }

    @Test
    void temporalBoundaryForcesReloadWithoutAPermissionVersionChange() {
        AtomicInteger repositoryLoads = new AtomicInteger();
        Instant boundary = Instant.parse("2026-07-20T12:00:00Z");
        var cache = new InMemoryPermissionGrantCache();
        PermissionGrantRepository grantRepository = (tenantId, membershipId, permissionVersion) -> {
            repositoryLoads.incrementAndGet();
            return new GrantSnapshot(membershipId, tenantId, permissionVersion, List.of(), boundary);
        };
        var beforeBoundary = new CachedPermissionGrantLoader((tenantId, membershipId) -> 1L,
            grantRepository, cache, Clock.fixed(boundary.minusSeconds(1), ZoneOffset.UTC));
        AuthorizationSubject subject = new AuthorizationSubject(10L, 20L, 30L, 40L, 1L, 1L, false);

        beforeBoundary.load(subject);
        beforeBoundary.load(subject);
        var atBoundary = new CachedPermissionGrantLoader((tenantId, membershipId) -> 1L,
            grantRepository, cache, Clock.fixed(boundary, ZoneOffset.UTC));
        atBoundary.load(subject);

        assertEquals(2, repositoryLoads.get());
    }
}
