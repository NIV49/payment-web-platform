package com.niv.payment.permission.service;

import com.niv.payment.permission.domain.AdministrationActor;

import java.util.List;

public record RoleConfigurationCommand(
    long tenantId,
    long roleId,
    long expectedRoleVersion,
    AdministrationActor actor,
    String name,
    int status,
    String remark,
    List<Long> menuIds,
    String reason,
    List<RoleGrantModels.Selection> grants
) {
    public RoleConfigurationCommand {
        if (tenantId <= 0 || roleId <= 0 || expectedRoleVersion < 0) {
            throw new IllegalArgumentException("Role configuration identity or version is invalid");
        }
        if (actor == null) {
            throw new IllegalArgumentException("Trusted administration actor is required");
        }
        name = name == null ? null : name.trim();
        if (name == null || name.isBlank() || name.length() > 128) {
            throw new IllegalArgumentException("Role name is required");
        }
        if (status != 0 && status != 1) {
            throw new IllegalArgumentException("Role status must be enabled or disabled");
        }
        remark = remark == null || remark.isBlank() ? null : remark.trim();
        if (remark != null && remark.length() > 500) {
            throw new IllegalArgumentException("Role remark is too long");
        }
        menuIds = menuIds == null ? List.of() : List.copyOf(menuIds);
        if (menuIds.size() > 2048 || menuIds.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new IllegalArgumentException("Role menu identifiers are invalid");
        }
        reason = reason == null ? null : reason.trim();
        if (reason == null || reason.isBlank() || reason.length() > 500) {
            throw new IllegalArgumentException("A concise role-configuration reason is required");
        }
        grants = grants == null ? List.of() : List.copyOf(grants);
    }
}
