package com.niv.payment.permission;

import com.niv.payment.permission.cache.JacksonGrantSnapshotCodec;
import com.niv.payment.permission.domain.DimensionScope;
import com.niv.payment.permission.domain.GrantSnapshot;
import com.niv.payment.permission.domain.PermissionCode;
import com.niv.payment.permission.domain.PermissionGrant;
import com.niv.payment.permission.domain.RiskLevel;
import com.niv.payment.permission.domain.ScopeDimension;
import com.niv.payment.permission.domain.ScopeMode;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JacksonGrantSnapshotCodecTest {
    @Test
    void roundTripsACompleteVersionedGrantSnapshot() {
        GrantSnapshot snapshot = new GrantSnapshot(1000L, 1L, 7L, List.of(
            new PermissionGrant(4001L, 2000L, PermissionCode.of("user:view"), RiskLevel.NORMAL,
                Set.of(ScopeDimension.TENANT),
                List.of(new DimensionScope(ScopeDimension.TENANT, ScopeMode.TENANT_ALL, Set.of())),
                false, false, true)), Instant.parse("2026-07-20T12:00:00Z"));

        JacksonGrantSnapshotCodec codec = new JacksonGrantSnapshotCodec(new ObjectMapper());

        assertEquals(snapshot, codec.decode(codec.encode(snapshot)));
    }
}
