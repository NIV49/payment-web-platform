package com.niv.payment.permission.persistence.mapper;

public record PermissionDefinitionRow(
    String permissionCode,
    String riskLevel,
    String requiredDimensions,
    boolean requiresStepUp,
    boolean requiresApproval
) {
}
