package com.niv.payment.permission.persistence.repository;

import com.niv.payment.permission.domain.CrossTenantMode;
import com.niv.payment.permission.domain.DimensionScope;
import com.niv.payment.permission.domain.GrantSnapshot;
import com.niv.payment.permission.domain.PermissionCode;
import com.niv.payment.permission.domain.PermissionGrant;
import com.niv.payment.permission.domain.RiskLevel;
import com.niv.payment.permission.domain.ScopeDimension;
import com.niv.payment.permission.domain.ScopeMode;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class GrantSnapshotAssembler {
    GrantSnapshot assemble(long tenantId,
                           long membershipId,
                           long permissionVersion,
                           OffsetDateTime evaluatedAt,
                           OffsetDateTime refreshAfter,
                           List<GrantRow> rows) {
        Map<Long, GrantAssembly> grants = new LinkedHashMap<>();
        Objects.requireNonNull(evaluatedAt, "evaluatedAt");
        if (refreshAfter != null && !refreshAfter.isAfter(evaluatedAt)) {
            throw new IllegalStateException("Permission refresh boundary is not in the future");
        }

        for (GrantRow row : rows) {
            Objects.requireNonNull(row, "row");
            if (!isActive(row, evaluatedAt)) {
                throw new IllegalStateException("Permission query returned a grant outside its validity window");
            }
            grants.computeIfAbsent(row.grantId(), ignored -> new GrantAssembly(row)).add(row);
        }

        return new GrantSnapshot(
            membershipId,
            tenantId,
            permissionVersion,
            grants.values().stream().map(GrantAssembly::build).toList(),
            refreshAfter == null ? null : refreshAfter.toInstant());
    }

    private static boolean isActive(GrantRow row, OffsetDateTime evaluatedAt) {
        return (row.validFrom() == null || !row.validFrom().isAfter(evaluatedAt))
            && (row.validUntil() == null || row.validUntil().isAfter(evaluatedAt));
    }

    record GrantRow(
        Long grantId,
        Long roleId,
        OffsetDateTime validFrom,
        OffsetDateTime validUntil,
        String permissionCode,
        String riskLevel,
        String crossTenantMode,
        String[] requiredDimensions,
        Boolean requiresStepUp,
        Boolean requiresApproval,
        Long dimensionId,
        String dimensionCode,
        String scopeMode,
        String targetRef
    ) {
    }

    private static final class GrantAssembly {
        private final GrantRow header;
        private final Map<ScopeDimension, ScopeAssembly> scopes = new EnumMap<>(ScopeDimension.class);

        private GrantAssembly(GrantRow header) {
            this.header = header;
        }

        private void add(GrantRow row) {
            validateHeader(row);
            if (row.dimensionId() == null) {
                return;
            }
            ScopeDimension dimension = ScopeDimension.valueOf(row.dimensionCode());
            ScopeMode mode = ScopeMode.valueOf(row.scopeMode());
            ScopeAssembly scope = scopes.computeIfAbsent(dimension, ignored -> new ScopeAssembly(mode));
            if (scope.mode != mode) {
                throw new IllegalStateException("One grant dimension was loaded with conflicting scope modes");
            }
            if (row.targetRef() != null) {
                scope.targets.add(row.targetRef());
            }
        }

        private PermissionGrant build() {
            List<DimensionScope> dimensions = scopes.entrySet().stream()
                .map(entry -> new DimensionScope(entry.getKey(), entry.getValue().mode, entry.getValue().targets))
                .toList();
            return new PermissionGrant(
                header.grantId(),
                header.roleId(),
                PermissionCode.of(header.permissionCode()),
                RiskLevel.valueOf(header.riskLevel()),
                CrossTenantMode.valueOf(header.crossTenantMode()),
                JooqPermissionCatalogRepository.dimensions(header.requiredDimensions()),
                dimensions,
                Boolean.TRUE.equals(header.requiresStepUp()),
                Boolean.TRUE.equals(header.requiresApproval()),
                true);
        }

        private void validateHeader(GrantRow row) {
            if (!Objects.equals(row.roleId(), header.roleId())
                || !Objects.equals(row.validFrom(), header.validFrom())
                || !Objects.equals(row.validUntil(), header.validUntil())
                || !Objects.equals(row.permissionCode(), header.permissionCode())
                || !Objects.equals(row.riskLevel(), header.riskLevel())
                || !Objects.equals(row.crossTenantMode(), header.crossTenantMode())
                || !Arrays.equals(row.requiredDimensions(), header.requiredDimensions())
                || !Objects.equals(row.requiresStepUp(), header.requiresStepUp())
                || !Objects.equals(row.requiresApproval(), header.requiresApproval())) {
                throw new IllegalStateException("Joined permission rows disagree on grant metadata");
            }
        }
    }

    private static final class ScopeAssembly {
        private final ScopeMode mode;
        private final Set<String> targets = new LinkedHashSet<>();

        private ScopeAssembly(ScopeMode mode) {
            this.mode = mode;
        }
    }
}
