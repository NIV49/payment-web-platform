package com.niv.payment.permission.persistence.mapper;

public record GrantRecordRow(
    long grantId,
    long roleId,
    String permissionCode,
    String riskLevel,
    String requiredDimensions,
    boolean requiresStepUp,
    boolean requiresApproval,
    Long dimensionId,
    String dimensionCode,
    String scopeMode,
    String targetRef
) {
}
