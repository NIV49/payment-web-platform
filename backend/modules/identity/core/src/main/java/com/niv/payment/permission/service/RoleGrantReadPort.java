package com.niv.payment.permission.service;

import com.niv.payment.permission.domain.AdministrationActor;

import java.util.List;

public interface RoleGrantReadPort {
    List<RoleGrantModels.GrantablePermission> findGrantablePermissions(
        long tenantId, AdministrationActor actor);

    RoleGrantModels.RoleGrants findRoleGrants(long tenantId, AdministrationActor actor, long roleId);
}
