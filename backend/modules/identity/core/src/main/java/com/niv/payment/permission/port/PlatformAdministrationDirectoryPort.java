package com.niv.payment.permission.port;

import com.niv.payment.permission.domain.AccountDomain;
import com.niv.payment.permission.domain.AdministrationActor;
import com.niv.payment.permission.service.IdentityModels;
import java.util.List;
import java.util.Objects;

public interface PlatformAdministrationDirectoryPort {

    enum ManagementMode {
        SAME_TENANT,
        READ_ONLY
    }

    record DirectoryScope(
            AccountDomain accountDomain,
            long tenantId,
            String tenantName,
            ManagementMode managementMode) {

        public DirectoryScope {
            Objects.requireNonNull(accountDomain, "accountDomain");
            if (tenantId <= 0) {
                throw new IllegalArgumentException("tenantId must be positive");
            }
            tenantName = requireText(tenantName, "tenantName");
            Objects.requireNonNull(managementMode, "managementMode");
        }
    }

    record RoleDirectoryQuery(
            AccountDomain accountDomain,
            Long tenantId,
            IdentityModels.RoleQuery roleQuery) {

        public RoleDirectoryQuery {
            Objects.requireNonNull(accountDomain, "accountDomain");
            Objects.requireNonNull(roleQuery, "roleQuery");
        }
    }

    record MenuDirectoryQuery(
            AccountDomain accountDomain,
            Long tenantId,
            boolean selectableOnly) {

        public MenuDirectoryQuery {
            Objects.requireNonNull(accountDomain, "accountDomain");
        }
    }

    record DirectoryRole(DirectoryScope scope, IdentityModels.Role role) {

        public DirectoryRole {
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(role, "role");
        }
    }

    record DirectoryMenu(DirectoryScope scope, IdentityModels.Menu menu) {

        public DirectoryMenu {
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(menu, "menu");
        }
    }

    IdentityModels.Page<DirectoryRole> findRoles(
            long sourceTenantId,
            AdministrationActor actor,
            RoleDirectoryQuery query);

    List<DirectoryMenu> findMenus(
            long sourceTenantId,
            AdministrationActor actor,
            MenuDirectoryQuery query);

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }
}
