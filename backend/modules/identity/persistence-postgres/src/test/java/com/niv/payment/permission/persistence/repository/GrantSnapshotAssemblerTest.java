package com.niv.payment.permission.persistence.repository;

import com.niv.payment.permission.domain.ScopeMode;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GrantSnapshotAssemblerTest {

    @Test
    void assignedScopeDoesNotAssembleStaticTargets() {
        OffsetDateTime evaluatedAt = OffsetDateTime.parse("2026-07-28T12:00:00Z");
        GrantSnapshotAssembler.GrantRow row = new GrantSnapshotAssembler.GrantRow(
            1L,
            10L,
            null,
            null,
            "order:view",
            "NORMAL",
            "SAME_TENANT_ONLY",
            new String[]{"MERCHANT"},
            false,
            false,
            100L,
            "MERCHANT",
            ScopeMode.ASSIGNED.name(),
            "stale-target");

        var snapshot = new GrantSnapshotAssembler().assemble(
            300L, 200L, 7L, evaluatedAt, null, List.of(row));

        assertEquals(Set.of(), snapshot.grants().getFirst().scopes().getFirst().targets());
    }

    @Test
    void specifiedScopeStillAssemblesStaticTargets() {
        OffsetDateTime evaluatedAt = OffsetDateTime.parse("2026-07-28T12:00:00Z");
        GrantSnapshotAssembler.GrantRow row = new GrantSnapshotAssembler.GrantRow(
            2L,
            10L,
            null,
            null,
            "order:view",
            "NORMAL",
            "SAME_TENANT_ONLY",
            new String[]{"MERCHANT"},
            false,
            false,
            101L,
            "MERCHANT",
            ScopeMode.SPECIFIED.name(),
            "M1");

        var snapshot = new GrantSnapshotAssembler().assemble(
            300L, 200L, 7L, evaluatedAt, null, List.of(row));

        assertEquals(Set.of("M1"), snapshot.grants().getFirst().scopes().getFirst().targets());
    }
}
