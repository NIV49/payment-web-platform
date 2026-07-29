package com.niv.payment.permission.service;

import com.niv.payment.permission.domain.PermissionGrant;

import java.util.List;

public record RoleGrantChangeCommand(
    long tenantId,
    long roleId,
    long expectedRoleVersion,
    long operatorMembershipId,
    List<PermissionGrant> grants
) {
    public RoleGrantChangeCommand {
        if (tenantId <= 0 || roleId <= 0 || operatorMembershipId <= 0 || expectedRoleVersion < 0) {
            throw new IllegalArgumentException("Role grant change identity or version is invalid");
        }
        grants = grants == null ? List.of() : List.copyOf(grants);
    }
}
