package com.niv.payment.permission.port;

import com.niv.payment.permission.domain.AdministrationActor;
import com.niv.payment.permission.service.IdentityModels;

public interface UserAdministrationPort {
    IdentityModels.Page<IdentityModels.User> findUsers(long tenantId, IdentityModels.UserQuery query);
    long createUser(long tenantId, AdministrationActor actor, IdentityModels.UserCreateCommand command);
    void updateUser(long tenantId, AdministrationActor actor, long userId,
                    IdentityModels.MembershipUpdateCommand command);
    long updateUserStatus(long tenantId, AdministrationActor actor, long userId, int status, long userVersion);
    void deleteUser(long tenantId, AdministrationActor actor, long userId, long expectedVersion);
}
