package com.niv.payment.permission.persistence.entity;

import java.time.Instant;

public record GrantTargetEntity(long id, long dimensionId, String targetRef, Instant createdAt) {
}
