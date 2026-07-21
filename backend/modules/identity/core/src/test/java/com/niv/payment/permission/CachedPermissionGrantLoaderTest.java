package com.niv.payment.permission;

import com.niv.payment.permission.application.CachedPermissionGrantLoader;
import com.niv.payment.permission.domain.AuthorizationSubject;
import com.niv.payment.permission.domain.GrantSnapshot;
import com.niv.payment.permission.port.MembershipVersionRepository;
import com.niv.payment.permission.port.PermissionGrantRepository;
import com.niv.payment.permission.port.StalePermissionVersionException;
import com.niv.payment.permission.support.InMemoryPermissionGrantCache;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void permissionVersionRaceReloadsTheLatestSnapshotOnce() {
        AtomicLong version = new AtomicLong(1L);
        AtomicInteger versionReads = new AtomicInteger();
        AtomicInteger repositoryLoads = new AtomicInteger();
        MembershipVersionRepository versionRepository = (tenantId, membershipId) -> {
            versionReads.incrementAndGet();
            return version.get();
        };
        PermissionGrantRepository grantRepository = (tenantId, membershipId, permissionVersion) -> {
            repositoryLoads.incrementAndGet();
            if (permissionVersion == 1L) {
                version.set(2L);
                throw new StalePermissionVersionException();
            }
            return new GrantSnapshot(membershipId, tenantId, permissionVersion, List.of());
        };
        var cache = new InMemoryPermissionGrantCache();
        var loader = new CachedPermissionGrantLoader(versionRepository, grantRepository, cache);
        AuthorizationSubject subject = new AuthorizationSubject(10L, 20L, 30L, 40L, 1L, 1L, false);

        GrantSnapshot snapshot = loader.load(subject);

        assertEquals(2L, snapshot.permissionVersion());
        assertEquals(2, versionReads.get());
        assertEquals(2, repositoryLoads.get());
        assertTrue(cache.find(subject.tenantId(), subject.membershipId(), 2L).isPresent());
    }

    @Test
    void cacheHitIsDiscardedWhenPermissionVersionChangesBeforeFinalConfirmation() {
        AtomicInteger versionReads = new AtomicInteger();
        AtomicInteger repositoryLoads = new AtomicInteger();
        MembershipVersionRepository versionRepository = (tenantId, membershipId) ->
            versionReads.incrementAndGet() == 1 ? 1L : 2L;
        PermissionGrantRepository grantRepository = (tenantId, membershipId, permissionVersion) -> {
            repositoryLoads.incrementAndGet();
            assertEquals(2L, permissionVersion);
            return new GrantSnapshot(membershipId, tenantId, permissionVersion, List.of());
        };
        var cache = new InMemoryPermissionGrantCache();
        cache.store(new GrantSnapshot(20L, 30L, 1L, List.of()));
        var loader = new CachedPermissionGrantLoader(versionRepository, grantRepository, cache);
        AuthorizationSubject subject = new AuthorizationSubject(10L, 20L, 30L, 40L, 1L, 1L, false);

        GrantSnapshot snapshot = loader.load(subject);

        assertEquals(2L, snapshot.permissionVersion());
        assertEquals(2, versionReads.get());
        assertEquals(1, repositoryLoads.get());
    }

    @Test
    void repeatedCacheHitVersionRacesStopAfterOneRetry() {
        AtomicLong version = new AtomicLong();
        AtomicInteger versionReads = new AtomicInteger();
        MembershipVersionRepository versionRepository = (tenantId, membershipId) -> {
            versionReads.incrementAndGet();
            return version.incrementAndGet();
        };
        var cache = new InMemoryPermissionGrantCache();
        cache.store(new GrantSnapshot(20L, 30L, 1L, List.of()));
        cache.store(new GrantSnapshot(20L, 30L, 2L, List.of()));
        var loader = new CachedPermissionGrantLoader(versionRepository,
            (tenantId, membershipId, permissionVersion) -> {
                throw new AssertionError("A prepared cache hit should not load the repository");
            }, cache);
        AuthorizationSubject subject = new AuthorizationSubject(10L, 20L, 30L, 40L, 1L, 1L, false);

        assertThrows(StalePermissionVersionException.class, () -> loader.load(subject));

        assertEquals(3, versionReads.get());
    }

    @Test
    void repeatedPermissionVersionRaceStopsAfterOneRetry() {
        AtomicLong version = new AtomicLong(1L);
        AtomicInteger versionReads = new AtomicInteger();
        AtomicInteger repositoryLoads = new AtomicInteger();
        MembershipVersionRepository versionRepository = (tenantId, membershipId) -> {
            versionReads.incrementAndGet();
            return version.get();
        };
        PermissionGrantRepository grantRepository = (tenantId, membershipId, permissionVersion) -> {
            repositoryLoads.incrementAndGet();
            version.incrementAndGet();
            throw new StalePermissionVersionException();
        };
        var loader = new CachedPermissionGrantLoader(versionRepository, grantRepository,
            new InMemoryPermissionGrantCache());
        AuthorizationSubject subject = new AuthorizationSubject(10L, 20L, 30L, 40L, 1L, 1L, false);

        assertThrows(StalePermissionVersionException.class, () -> loader.load(subject));

        assertEquals(2, versionReads.get());
        assertEquals(2, repositoryLoads.get());
    }

    @Test
    void temporalSnapshotIsNotCachedWhenApplicationClockIsBehindDatabaseBoundary() {
        AtomicInteger repositoryLoads = new AtomicInteger();
        Instant boundaryFarInTheFuture = Instant.parse("2100-01-01T00:00:00Z");
        var cache = new InMemoryPermissionGrantCache();
        PermissionGrantRepository grantRepository = (tenantId, membershipId, permissionVersion) -> {
            repositoryLoads.incrementAndGet();
            return new GrantSnapshot(membershipId, tenantId, permissionVersion, List.of(), boundaryFarInTheFuture);
        };
        var loader = new CachedPermissionGrantLoader((tenantId, membershipId) -> 1L, grantRepository, cache);
        AuthorizationSubject subject = new AuthorizationSubject(10L, 20L, 30L, 40L, 1L, 1L, false);

        loader.load(subject);
        loader.load(subject);

        assertEquals(2, repositoryLoads.get());
        assertTrue(cache.find(subject.tenantId(), subject.membershipId(), 1L).isEmpty());
    }

    @Test
    void temporalSnapshotIsNotRejectedOrCachedWhenApplicationClockIsAheadOfDatabaseBoundary() {
        AtomicInteger repositoryLoads = new AtomicInteger();
        Instant boundaryFarInThePast = Instant.parse("2000-01-01T00:00:00Z");
        var cache = new InMemoryPermissionGrantCache();
        PermissionGrantRepository grantRepository = (tenantId, membershipId, permissionVersion) -> {
            repositoryLoads.incrementAndGet();
            return new GrantSnapshot(membershipId, tenantId, permissionVersion, List.of(), boundaryFarInThePast);
        };
        var loader = new CachedPermissionGrantLoader((tenantId, membershipId) -> 1L, grantRepository, cache);
        AuthorizationSubject subject = new AuthorizationSubject(10L, 20L, 30L, 40L, 1L, 1L, false);

        loader.load(subject);
        loader.load(subject);

        assertEquals(2, repositoryLoads.get());
        assertTrue(cache.find(subject.tenantId(), subject.membershipId(), 1L).isEmpty());
    }

    @Test
    void preExistingTemporalCacheEntryIsNeverAccepted() {
        AtomicInteger repositoryLoads = new AtomicInteger();
        var cache = new InMemoryPermissionGrantCache();
        cache.store(new GrantSnapshot(20L, 30L, 1L, List.of(), Instant.parse("2100-01-01T00:00:00Z")));
        PermissionGrantRepository grantRepository = (tenantId, membershipId, permissionVersion) -> {
            repositoryLoads.incrementAndGet();
            return new GrantSnapshot(membershipId, tenantId, permissionVersion, List.of());
        };
        var loader = new CachedPermissionGrantLoader((tenantId, membershipId) -> 1L, grantRepository, cache);
        AuthorizationSubject subject = new AuthorizationSubject(10L, 20L, 30L, 40L, 1L, 1L, false);

        GrantSnapshot snapshot = loader.load(subject);

        assertEquals(1, repositoryLoads.get());
        assertNull(snapshot.refreshAfter());
    }
}
