package com.niv.payment.permission;

import com.niv.payment.permission.persistence.mapper.GrantRecordRow;
import com.niv.payment.permission.persistence.mapper.PermissionGrantMapper;
import com.niv.payment.permission.persistence.repository.MyBatisPermissionGrantRepository;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MyBatisPermissionGrantRepositoryTest {

    @Test
    void groupsJoinedRowsIntoOneAtomicGrant() {
        PermissionGrantMapper mapper = (tenantId, membershipId) -> List.of(
            row(11L, "MERCHANT", "SPECIFIED", "M1"),
            row(11L, "MARKET", "SPECIFIED", "PK"));

        var repository = new MyBatisPermissionGrantRepository(mapper);
        var snapshot = repository.load(3L, 2L, 7L);

        assertEquals(1, snapshot.grants().size());
        assertEquals(2, snapshot.grants().get(0).scopes().size());
        assertEquals("SAME_TENANT_ONLY", snapshot.grants().get(0).crossTenantMode().name());
        assertEquals(7L, snapshot.permissionVersion());
        assertEquals(OffsetDateTime.of(2026, 7, 20, 12, 0, 0, 0, ZoneOffset.UTC).toInstant(),
            snapshot.refreshAfter());
    }

    @Test
    void normalizesMyBatisAllNullSentinelToAnEmptySnapshot() {
        PermissionGrantMapper mapper = (tenantId, membershipId) -> Collections.singletonList(null);

        var snapshot = new MyBatisPermissionGrantRepository(mapper).load(3L, 2L, 7L);

        assertEquals(List.of(), snapshot.grants());
        assertNull(snapshot.refreshAfter());
    }

    @Test
    void preservesTemporalBoundaryWhenNoGrantIsCurrentlyActive() {
        OffsetDateTime boundary = OffsetDateTime.of(2026, 7, 21, 12, 0, 0, 0, ZoneOffset.UTC);
        PermissionGrantMapper mapper = (tenantId, membershipId) -> List.of(
            new GrantRecordRow(null, null, null, null, null, null, null, null,
                null, null, null, null, boundary));

        var snapshot = new MyBatisPermissionGrantRepository(mapper).load(3L, 2L, 7L);

        assertEquals(List.of(), snapshot.grants());
        assertEquals(boundary.toInstant(), snapshot.refreshAfter());
    }

    private static GrantRecordRow row(long grantId, String dimension, String mode, String target) {
        return new GrantRecordRow(grantId, 21L, "payout:approve", "FUND", "SAME_TENANT_ONLY",
            "MERCHANT,MARKET", true, true, 31L + dimension.hashCode(), dimension, mode, target,
            OffsetDateTime.of(2026, 7, 20, 12, 0, 0, 0, ZoneOffset.UTC));
    }
}
