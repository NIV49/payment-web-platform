package com.niv.payment.permission;

import com.niv.payment.permission.application.CachedPermissionGrantLoader;
import com.niv.payment.permission.domain.AuthorizationSubject;
import com.niv.payment.permission.domain.GrantSnapshot;
import com.niv.payment.permission.port.MembershipVersionRepository;
import com.niv.payment.permission.port.PermissionGrantRepository;
import com.niv.payment.permission.support.InMemoryPermissionGrantCache;
import org.junit.jupiter.api.Test;

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
}
