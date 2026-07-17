package com.niv.payment.permission.persistence.entity;

import java.time.Instant;

public record GrantDimensionEntity(
    long id,
    long grantId,
    String dimensionCode,
    String scopeMode,
    Instant createdAt
) {
}
