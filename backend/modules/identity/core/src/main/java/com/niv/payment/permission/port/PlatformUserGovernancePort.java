package com.niv.payment.permission.port;

import com.niv.payment.permission.domain.AccountDomain;
import com.niv.payment.permission.domain.AdministrationActor;
import com.niv.payment.permission.service.IdentityModels;

import java.time.Instant;
import java.util.List;

/** Platform control-plane boundary for cross-domain reads and tenant administrator writes. */
public interface PlatformUserGovernancePort {
    IdentityModels.Page<DirectoryUser> findUsers(
        long sourceTenantId, AdministrationActor actor, DirectoryQuery query);

    List<TenantOption> findTargetTenants(
        long sourceTenantId, AdministrationActor actor, AccountDomain accountDomain);

    long createTenantAdministrator(
        long sourceTenantId, AdministrationActor actor, CreateAdministratorCommand command);

    void updateTenantAdministrator(
        long sourceTenantId, AdministrationActor actor, long userId,
        UpdateAdministratorCommand command);

    IdentityModels.PasswordResetResult resetUserPassword(
        long sourceTenantId, AdministrationActor actor, long userId,
        PasswordResetCommand command);

    record DirectoryQuery(AccountDomain accountDomain, Long tenantId, Long departmentId,
                          String email, String name, Integer status, int page, int pageSize) { }

    record DirectoryUser(long id, long membershipId, AccountDomain accountDomain,
                         long tenantId, String tenantName, String email, String name,
                         String remark,
                         Long departmentId, String departmentName, List<Long> roleIds,
                         List<String> roleNames, int status, String identityStatus,
                         boolean systemAdministrator, long membershipVersion,
                         long identityVersion, long credentialVersion, Instant createdAt) {
        public DirectoryUser {
            roleIds = List.copyOf(roleIds);
            roleNames = List.copyOf(roleNames);
        }
    }

    record TenantOption(long id, AccountDomain accountDomain, String code, String name) { }

    record CreateAdministratorCommand(AccountDomain accountDomain, long tenantId,
                                      String email, String name, int status) { }

    record UpdateAdministratorCommand(AccountDomain accountDomain, long tenantId,
                                      String email, String name, int status,
                                      long membershipVersion, long identityVersion,
                                      long credentialVersion) { }

    record PasswordResetCommand(AccountDomain accountDomain, long tenantId,
                                long credentialVersion, String password) {
        @Override
        public String toString() {
            return "PasswordResetCommand[accountDomain=" + accountDomain
                + ", tenantId=" + tenantId + ", credentialVersion=" + credentialVersion
                + ", password=<redacted>]";
        }
    }
}
