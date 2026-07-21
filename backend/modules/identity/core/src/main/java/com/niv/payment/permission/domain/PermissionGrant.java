package com.niv.payment.permission.domain;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record PermissionGrant(
    long id,
    long roleId,
    PermissionCode permission,
    RiskLevel riskLevel,
    CrossTenantMode crossTenantMode,
    Set<ScopeDimension> requiredDimensions,
    List<DimensionScope> scopes,
    boolean requiresStepUp,
    boolean requiresApproval,
    boolean active
) {
    public PermissionGrant {
        if (id <= 0 || roleId <= 0) {
            throw new IllegalArgumentException("Grant and role identifiers must be positive");
        }
        Objects.requireNonNull(permission, "permission");
        Objects.requireNonNull(riskLevel, "riskLevel");
        crossTenantMode = crossTenantMode == null ? CrossTenantMode.SAME_TENANT_ONLY : crossTenantMode;
        requiredDimensions = requiredDimensions == null ? Set.of() : Set.copyOf(requiredDimensions);
        scopes = scopes == null ? List.of() : List.copyOf(scopes);
        if (riskLevel == RiskLevel.FUND && crossTenantMode != CrossTenantMode.SAME_TENANT_ONLY) {
            throw new IllegalArgumentException("FUND permissions must remain tenant-bound");
        }
        if (crossTenantMode == CrossTenantMode.RELATED_PARTY_READ && !permission.action().readOnly()) {
            throw new IllegalArgumentException(
                "RELATED_PARTY_READ grants must use a controlled read-only action");
        }

        Set<ScopeDimension> dimensions = new HashSet<>();
        for (DimensionScope scope : scopes) {
            if (!dimensions.add(scope.dimension())) {
                throw new IllegalArgumentException("A grant cannot declare a dimension more than once: " + scope.dimension());
            }
        }
        if (!dimensions.containsAll(requiredDimensions)) {
            throw new IllegalArgumentException("Every required dimension must have a scope in the same grant");
        }
    }

    public PermissionGrant(long id,
                           long roleId,
                           PermissionCode permission,
                           RiskLevel riskLevel,
                           Set<ScopeDimension> requiredDimensions,
                           List<DimensionScope> scopes,
                           boolean requiresStepUp,
                           boolean requiresApproval,
                           boolean active) {
        this(id, roleId, permission, riskLevel, CrossTenantMode.SAME_TENANT_ONLY, requiredDimensions, scopes,
            requiresStepUp, requiresApproval, active);
    }

    public boolean needsStepUp() {
        return riskLevel == RiskLevel.FUND || requiresStepUp;
    }
}
