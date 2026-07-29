package com.niv.payment.permission.persistence.repository;

import com.niv.payment.permission.domain.CrossTenantMode;
import com.niv.payment.permission.domain.PermissionCode;
import com.niv.payment.permission.domain.PermissionDefinition;
import com.niv.payment.permission.domain.RiskLevel;
import com.niv.payment.permission.domain.ScopeDimension;
import com.niv.payment.permission.port.PermissionCatalogPort;
import org.jooq.DSLContext;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_PERMISSION;

public final class JooqPermissionCatalogRepository implements PermissionCatalogPort {
    private static final String ACTIVE = "ACTIVE";

    private final DSLContext dsl;

    public JooqPermissionCatalogRepository(DSLContext dsl) {
        this.dsl = Objects.requireNonNull(dsl, "dsl");
    }

    @Override
    public PermissionDefinition require(PermissionCode code) {
        Objects.requireNonNull(code, "code");
        var record = dsl.select(
                IAM_PERMISSION.PERMISSION_CODE,
                IAM_PERMISSION.RISK_LEVEL,
                IAM_PERMISSION.CROSS_TENANT_MODE,
                IAM_PERMISSION.REQUIRED_DIMENSIONS,
                IAM_PERMISSION.REQUIRES_STEP_UP,
                IAM_PERMISSION.REQUIRES_APPROVAL)
            .from(IAM_PERMISSION)
            .where(IAM_PERMISSION.PERMISSION_CODE.eq(code.value())
                .and(IAM_PERMISSION.STATUS.eq(ACTIVE)))
            .fetchOptional()
            .orElseThrow(() -> new IllegalArgumentException(
                "Unknown or disabled permission: " + code));

        if (!Objects.equals(record.get(IAM_PERMISSION.PERMISSION_CODE), code.value())) {
            throw new IllegalStateException("Permission catalog returned the wrong definition");
        }
        return new PermissionDefinition(
            code,
            RiskLevel.valueOf(record.get(IAM_PERMISSION.RISK_LEVEL)),
            CrossTenantMode.valueOf(record.get(IAM_PERMISSION.CROSS_TENANT_MODE)),
            dimensions(record.get(IAM_PERMISSION.REQUIRED_DIMENSIONS)),
            Boolean.TRUE.equals(record.get(IAM_PERMISSION.REQUIRES_STEP_UP)),
            Boolean.TRUE.equals(record.get(IAM_PERMISSION.REQUIRES_APPROVAL)),
            true);
    }

    static Set<ScopeDimension> dimensions(String[] values) {
        if (values == null || values.length == 0) {
            return Set.of();
        }
        Set<ScopeDimension> dimensions = new LinkedHashSet<>();
        for (String value : values) {
            dimensions.add(ScopeDimension.valueOf(value));
        }
        return Set.copyOf(dimensions);
    }
}
