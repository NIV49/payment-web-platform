package com.niv.payment.permission.domain;

import java.util.Objects;
import java.util.Set;

public record PermissionDefinition(
    PermissionCode code,
    RiskLevel riskLevel,
    Set<ScopeDimension> requiredDimensions,
    boolean requiresStepUp,
    boolean requiresApproval,
    boolean active
) {
    public PermissionDefinition {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(riskLevel, "riskLevel");
        requiredDimensions = requiredDimensions == null ? Set.of() : Set.copyOf(requiredDimensions);
        if (riskLevel == RiskLevel.FUND && !requiresStepUp) {
            throw new IllegalArgumentException("FUND permission definitions must require step-up authentication");
        }
    }
}
