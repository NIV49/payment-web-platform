package com.niv.payment.permission.persistence.entity;

import java.time.Instant;
import java.util.Set;

public record PermissionEntity(
    long id,
    String permissionCode,
    String resourceCode,
    String actionCode,
    String riskLevel,
    Set<String> requiredDimensions,
    boolean requiresStepUp,
    boolean requiresApproval,
    String status,
    String description,
    Instant createdAt,
    Instant updatedAt,
    long rowVersion
) {
    public PermissionEntity {
        requiredDimensions = requiredDimensions == null ? Set.of() : Set.copyOf(requiredDimensions);
    }
}
