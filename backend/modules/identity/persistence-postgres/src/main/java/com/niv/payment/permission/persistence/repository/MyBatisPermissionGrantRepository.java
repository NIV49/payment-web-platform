package com.niv.payment.permission.persistence.repository;

import com.niv.payment.permission.domain.CrossTenantMode;
import com.niv.payment.permission.domain.DimensionScope;
import com.niv.payment.permission.domain.GrantSnapshot;
import com.niv.payment.permission.domain.PermissionCode;
import com.niv.payment.permission.domain.PermissionGrant;
import com.niv.payment.permission.domain.RiskLevel;
import com.niv.payment.permission.domain.ScopeDimension;
import com.niv.payment.permission.domain.ScopeMode;
import com.niv.payment.permission.persistence.mapper.GrantRecordRow;
import com.niv.payment.permission.persistence.mapper.PermissionGrantMapper;
import com.niv.payment.permission.port.PermissionGrantRepository;

import java.time.OffsetDateTime;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class MyBatisPermissionGrantRepository implements PermissionGrantRepository {
    private final PermissionGrantMapper mapper;

    public MyBatisPermissionGrantRepository(PermissionGrantMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public GrantSnapshot load(long tenantId, long membershipId, long permissionVersion) {
        Map<Long, GrantAssembly> grants = new LinkedHashMap<>();
        OffsetDateTime refreshAfter = null;
        for (GrantRecordRow row : mapper.findActiveGrantRows(tenantId, membershipId)) {
            if (row == null) {
                continue;
            }
            if (row.refreshAfter() != null) {
                if (refreshAfter != null && !refreshAfter.equals(row.refreshAfter())) {
                    throw new IllegalStateException("Permission rows disagree on the temporal refresh boundary");
                }
                refreshAfter = row.refreshAfter();
            }
            if (row.grantId() != null) {
                grants.computeIfAbsent(row.grantId(), ignored -> new GrantAssembly(row)).add(row);
            }
        }
        return new GrantSnapshot(membershipId, tenantId, permissionVersion,
            grants.values().stream().map(GrantAssembly::build).toList(),
            refreshAfter == null ? null : refreshAfter.toInstant());
    }

    private static final class GrantAssembly {
        private final GrantRecordRow header;
        private final Map<ScopeDimension, ScopeAssembly> scopes = new EnumMap<>(ScopeDimension.class);

        private GrantAssembly(GrantRecordRow header) {
            this.header = header;
        }

        private void add(GrantRecordRow row) {
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
            return new PermissionGrant(header.grantId(), header.roleId(), PermissionCode.of(header.permissionCode()),
                RiskLevel.valueOf(header.riskLevel()), CrossTenantMode.valueOf(header.crossTenantMode()),
                parseDimensions(header.requiredDimensions()), dimensions, Boolean.TRUE.equals(header.requiresStepUp()),
                Boolean.TRUE.equals(header.requiresApproval()), true);
        }

        private void validateHeader(GrantRecordRow row) {
            if (!Objects.equals(row.roleId(), header.roleId())
                || !Objects.equals(row.permissionCode(), header.permissionCode())
                || !Objects.equals(row.riskLevel(), header.riskLevel())
                || !Objects.equals(row.crossTenantMode(), header.crossTenantMode())
                || !Objects.equals(row.requiredDimensions(), header.requiredDimensions())
                || !Objects.equals(row.requiresStepUp(), header.requiresStepUp())
                || !Objects.equals(row.requiresApproval(), header.requiresApproval())) {
                throw new IllegalStateException("Joined permission rows disagree on grant metadata");
            }
        }

        private static Set<ScopeDimension> parseDimensions(String value) {
            if (value == null || value.isBlank()) {
                return Set.of();
            }
            Set<ScopeDimension> dimensions = new LinkedHashSet<>();
            for (String item : value.split(",")) {
                dimensions.add(ScopeDimension.valueOf(item.trim()));
            }
            return Set.copyOf(dimensions);
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
