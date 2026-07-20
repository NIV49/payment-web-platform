package com.niv.payment.permission.domain;

import java.util.Objects;
import java.util.Set;

public record PermissionDefinition(
    PermissionCode code,
    RiskLevel riskLevel,
    CrossTenantMode crossTenantMode,
    Set<ScopeDimension> requiredDimensions,
    boolean requiresStepUp,
    boolean requiresApproval,
    boolean active
) {
    public PermissionDefinition {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(riskLevel, "riskLevel");
        crossTenantMode = crossTenantMode == null ? CrossTenantMode.SAME_TENANT_ONLY : crossTenantMode;
        requiredDimensions = requiredDimensions == null ? Set.of() : Set.copyOf(requiredDimensions);
        if (riskLevel == RiskLevel.FUND && !requiresStepUp) {
            throw new IllegalArgumentException("FUND permission definitions must require step-up authentication");
        }
        if (riskLevel == RiskLevel.FUND && crossTenantMode != CrossTenantMode.SAME_TENANT_ONLY) {
            throw new IllegalArgumentException("FUND permission definitions must remain tenant-bound");
        }
    }

    public PermissionDefinition(PermissionCode code,
                                RiskLevel riskLevel,
                                Set<ScopeDimension> requiredDimensions,
                                boolean requiresStepUp,
                                boolean requiresApproval,
                                boolean active) {
        this(code, riskLevel, CrossTenantMode.SAME_TENANT_ONLY, requiredDimensions, requiresStepUp,
            requiresApproval, active);
    }
}
