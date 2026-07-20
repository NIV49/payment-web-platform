package com.niv.payment.permission.persistence.mapper;

import java.time.OffsetDateTime;

public record GrantRecordRow(
    Long grantId,
    Long roleId,
    String permissionCode,
    String riskLevel,
    String crossTenantMode,
    String requiredDimensions,
    Boolean requiresStepUp,
    Boolean requiresApproval,
    Long dimensionId,
    String dimensionCode,
    String scopeMode,
    String targetRef,
    OffsetDateTime refreshAfter
) {
}
