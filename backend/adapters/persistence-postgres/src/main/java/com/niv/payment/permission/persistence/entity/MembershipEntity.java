package com.niv.payment.permission.persistence.entity;

import java.time.Instant;

public record MembershipEntity(
    long id,
    long tenantId,
    long userId,
    Long departmentId,
    String status,
    long permissionVersion,
    long sessionVersion,
    Instant createdAt,
    Instant updatedAt,
    long rowVersion
) {
}
