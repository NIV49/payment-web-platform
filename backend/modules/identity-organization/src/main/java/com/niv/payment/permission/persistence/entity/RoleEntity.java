package com.niv.payment.permission.persistence.entity;

import java.time.Instant;

public record RoleEntity(
    long id,
    long tenantId,
    String roleCode,
    String roleName,
    String applicableTenantType,
    boolean assignable,
    boolean systemRole,
    String status,
    Instant createdAt,
    Instant updatedAt,
    long rowVersion
) {
}
