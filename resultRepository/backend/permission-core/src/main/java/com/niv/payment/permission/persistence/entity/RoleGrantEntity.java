package com.niv.payment.permission.persistence.entity;

import java.time.Instant;

public record RoleGrantEntity(
    long id,
    long tenantId,
    long roleId,
    long permissionId,
    String grantKey,
    String status,
    Instant validFrom,
    Instant validUntil,
    Long createdBy,
    Instant createdAt,
    Long updatedBy,
    Instant updatedAt,
    long rowVersion
) {
}
