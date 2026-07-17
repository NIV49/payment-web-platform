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
        requiredDimensions = requiredDimensions == null ? Set.of() : Set.copyOf(requiredDimensions);
        scopes = scopes == null ? List.of() : List.copyOf(scopes);

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

    public boolean needsStepUp() {
        return riskLevel == RiskLevel.FUND || requiresStepUp;
    }
}
