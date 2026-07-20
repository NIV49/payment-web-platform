package com.niv.payment.permission.port;

import com.niv.payment.permission.service.IdentityModels;

public interface UserAdministrationPort {
    IdentityModels.Page<IdentityModels.User> findUsers(long tenantId, IdentityModels.UserQuery query);
    java.util.List<Long> findUserRoleIds(long tenantId, long userId);
    long createUser(long tenantId, long operatorMembershipId, IdentityModels.UserCommand command);
    void updateUser(long tenantId, long operatorMembershipId, long userId, IdentityModels.UserCommand command);
    long updateUserStatus(long tenantId, long operatorMembershipId, long userId, int status, long userVersion);
    void deleteUser(long tenantId, long operatorMembershipId, long userId);
}
