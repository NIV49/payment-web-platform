package com.niv.payment.permission.port;

import com.niv.payment.permission.service.IdentityModels;

public interface RoleAdministrationPort {
    IdentityModels.Page<IdentityModels.Role> findRoles(long tenantId, IdentityModels.RoleQuery query);
    long createRole(long tenantId, long operatorMembershipId, IdentityModels.RoleCommand command);
    void updateRole(long tenantId, long operatorMembershipId, long roleId, IdentityModels.RoleCommand command);
    void updateRoleStatus(long tenantId, long operatorMembershipId, long roleId, int status);
    void deleteRole(long tenantId, long operatorMembershipId, long roleId);
}
