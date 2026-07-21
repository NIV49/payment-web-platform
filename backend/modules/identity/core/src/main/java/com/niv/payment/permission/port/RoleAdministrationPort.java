package com.niv.payment.permission.port;

import com.niv.payment.permission.domain.AdministrationActor;
import com.niv.payment.permission.service.IdentityModels;

public interface RoleAdministrationPort {
    IdentityModels.Page<IdentityModels.Role> findRoles(long tenantId, IdentityModels.RoleQuery query);
    long createRole(long tenantId, AdministrationActor actor, IdentityModels.RoleCommand command);
    void updateRole(long tenantId, AdministrationActor actor, long roleId,
                    IdentityModels.RoleCommand command, long expectedVersion);
    void updateRoleStatus(long tenantId, AdministrationActor actor, long roleId,
                          int status, long expectedVersion);
    void deleteRole(long tenantId, AdministrationActor actor, long roleId, long expectedVersion);
}
