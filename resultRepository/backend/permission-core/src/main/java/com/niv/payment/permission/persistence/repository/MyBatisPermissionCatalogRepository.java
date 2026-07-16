package com.niv.payment.permission.persistence.repository;

import com.niv.payment.permission.domain.PermissionCode;
import com.niv.payment.permission.domain.PermissionDefinition;
import com.niv.payment.permission.domain.RiskLevel;
import com.niv.payment.permission.domain.ScopeDimension;
import com.niv.payment.permission.persistence.mapper.PermissionCatalogMapper;
import com.niv.payment.permission.persistence.mapper.PermissionDefinitionRow;
import com.niv.payment.permission.port.PermissionCatalogPort;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

public final class MyBatisPermissionCatalogRepository implements PermissionCatalogPort {
    private final PermissionCatalogMapper mapper;

    public MyBatisPermissionCatalogRepository(PermissionCatalogMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public PermissionDefinition require(PermissionCode code) {
        PermissionDefinitionRow row = mapper.findActiveByCode(code.value())
            .orElseThrow(() -> new IllegalArgumentException("Unknown or disabled permission: " + code));
        if (!row.permissionCode().equals(code.value())) {
            throw new IllegalStateException("Permission catalog returned the wrong definition");
        }
        return new PermissionDefinition(code, RiskLevel.valueOf(row.riskLevel()),
            parseDimensions(row.requiredDimensions()), row.requiresStepUp(), row.requiresApproval(), true);
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
