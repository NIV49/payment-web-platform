package com.niv.payment.permission.service;

import com.niv.payment.permission.domain.AdministrationActor;

import java.util.List;

public record RoleGrantChangeCommand(
    long tenantId,
    long roleId,
    long expectedRoleVersion,
    AdministrationActor actor,
    String reason,
    List<RoleGrantModels.Selection> grants
) {
    public RoleGrantChangeCommand {
        if (tenantId <= 0 || roleId <= 0 || expectedRoleVersion < 0) {
            throw new IllegalArgumentException("Role grant change identity or version is invalid");
        }
        if (actor == null) {
            throw new IllegalArgumentException("Trusted administration actor is required");
        }
        reason = reason == null ? null : reason.trim();
        if (reason == null || reason.isBlank() || reason.length() > 500) {
            throw new IllegalArgumentException("A concise grant-change reason is required");
        }
        grants = grants == null ? List.of() : List.copyOf(grants);
    }
}
