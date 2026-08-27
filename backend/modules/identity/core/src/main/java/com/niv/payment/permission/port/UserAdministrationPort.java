package com.niv.payment.permission.port;

import com.niv.payment.permission.domain.AdministrationActor;
import com.niv.payment.permission.service.IdentityModels;

import java.util.List;

public interface UserAdministrationPort {
    IdentityModels.Page<IdentityModels.User> findUsers(long tenantId, IdentityModels.UserQuery query);
    IdentityModels.Page<IdentityModels.User> findRoleMembers(
        long tenantId, long roleId, boolean assigned, IdentityModels.UserQuery query);
    long createUser(long tenantId, AdministrationActor actor, IdentityModels.UserCreateCommand command);
    void updateUser(long tenantId, AdministrationActor actor, long userId,
                    IdentityModels.MembershipUpdateCommand command);
    long replaceUserRoles(long tenantId, AdministrationActor actor, long userId,
                          List<Long> roleIds, long userVersion);
    void updateRoleMembers(long tenantId, AdministrationActor actor, long roleId,
                           List<IdentityModels.RoleMemberChange> members);
    long updateUserStatus(long tenantId, AdministrationActor actor, long userId, int status, long userVersion);
    IdentityModels.PasswordResetResult resetUserPassword(long tenantId, AdministrationActor actor,
                                                         long userId, long credentialVersion,
                                                         String password);
    void deleteUser(long tenantId, AdministrationActor actor, long userId, long expectedVersion);
}
