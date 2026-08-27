package com.niv.payment.permission.backoffice;

import java.util.List;
import java.util.regex.Pattern;
import com.niv.payment.permission.domain.AccountDomain;

/** Explicit same-tenant administration policy for MERCHANT and AGENT composition roots. */
final class BackofficeAdministrationPermissionPolicy {
    private final AccountDomain accountDomain;

    BackofficeAdministrationPermissionPolicy(AccountDomain accountDomain) {
        this.accountDomain = accountDomain;
    }
    private static final Pattern USER_ITEM =
        Pattern.compile("^/api/system/user/[1-9][0-9]*$");
    private static final Pattern USER_STATUS =
        Pattern.compile("^/api/system/user/[1-9][0-9]*/status$");
    private static final Pattern USER_ROLES =
        Pattern.compile("^/api/system/user/[1-9][0-9]*/roles$");
    private static final Pattern USER_PASSWORD_RESET =
        Pattern.compile("^/api/system/user/[1-9][0-9]*/password/reset$");
    private static final Pattern ROLE_ITEM =
        Pattern.compile("^/api/system/role/[1-9][0-9]*$");
    private static final Pattern ROLE_STATUS =
        Pattern.compile("^/api/system/role/[1-9][0-9]*/status$");
    private static final Pattern ROLE_MEMBERS =
        Pattern.compile("^/api/system/role/[1-9][0-9]*/members$");
    private static final Pattern ROLE_GRANTS =
        Pattern.compile("^/api/v1/iam/roles/[1-9][0-9]*/grants$");
    private static final Pattern ROLE_CONFIGURATION =
        Pattern.compile("^/api/v1/iam/roles/[1-9][0-9]*/configuration$");

    List<String> requiredPermissions(String method, String path) {
        if (sessionOnly(method, path)) {
            return List.of();
        }

        if ("GET".equals(method) && "/api/system/user/list".equals(path)) {
            return List.of("user:view");
        }
        if ("POST".equals(method) && "/api/system/user".equals(path)) {
            return List.of("user:create", "user:assign-role");
        }
        if ("PUT".equals(method) && USER_ITEM.matcher(path).matches()) {
            return List.of("user:update", "user:disable", "user:assign-role");
        }
        if ("PATCH".equals(method) && USER_STATUS.matcher(path).matches()) {
            return List.of("user:disable");
        }
        if ("PUT".equals(method) && USER_ROLES.matcher(path).matches()) {
            return List.of("user:assign-role");
        }
        if ("POST".equals(method) && USER_PASSWORD_RESET.matcher(path).matches()) {
            return List.of("user:update");
        }
        if ("DELETE".equals(method) && USER_ITEM.matcher(path).matches()) {
            return List.of("user:delete");
        }

        if ("GET".equals(method) && "/api/system/role/list".equals(path)) {
            return List.of("role:view");
        }
        if ("GET".equals(method) && ROLE_MEMBERS.matcher(path).matches()) {
            return List.of("user:view", "role:view");
        }
        if ("PATCH".equals(method) && ROLE_MEMBERS.matcher(path).matches()) {
            return List.of("user:assign-role", "role:view");
        }
        if ("POST".equals(method) && "/api/system/role".equals(path)) {
            return List.of("role:create");
        }
        if (("PUT".equals(method) && ROLE_ITEM.matcher(path).matches())
            || ("PATCH".equals(method) && ROLE_STATUS.matcher(path).matches())) {
            return List.of("role:update");
        }
        if ("DELETE".equals(method) && ROLE_ITEM.matcher(path).matches()) {
            return List.of("role:delete");
        }
        if ("GET".equals(method) && "/api/v1/iam/permissions/grantable".equals(path)) {
            return List.of("role:view", "role:grant-update");
        }
        if (("GET".equals(method) || "PUT".equals(method))
            && ROLE_GRANTS.matcher(path).matches()) {
            return List.of("role:view", "role:grant-update");
        }
        if ("PUT".equals(method) && ROLE_CONFIGURATION.matcher(path).matches()) {
            return List.of("role:view", "role:update", "menu:view", "role:grant-update");
        }
        if ("POST".equals(method) && "/api/v1/iam/roles/configuration".equals(path)) {
            return List.of("role:view", "role:create", "menu:view", "role:grant-update");
        }

        if ("GET".equals(method) && "/api/system/dept/list".equals(path)) {
            return List.of("department:view");
        }
        if ("GET".equals(method) && "/api/system/menu/list".equals(path)) {
            return List.of("menu:view");
        }
        if ("GET".equals(method) && ("/api/system/dictionary-types/options".equals(path)
            || "/api/system/dictionary-data".equals(path))) {
            return List.of("dictionary-data:view");
        }
        if ("POST".equals(method) && "/api/dict/queryBatch".equals(path)) {
            return List.of("dictionary-data:view");
        }
        if (accountDomain == AccountDomain.MERCHANT
            && "GET".equals(method) && "/api/merchant/application".equals(path)) {
            return List.of("merchant:self-view");
        }
        if (accountDomain == AccountDomain.MERCHANT
            && "POST".equals(method) && "/api/merchant/application/submissions".equals(path)) {
            // The strict request shape fixes submit versus resubmit. The repository proves
            // that exact permission through the protected role in the write transaction.
            return List.of();
        }
        throw new BackofficeAccessDeniedException();
    }

    private static boolean sessionOnly(String method, String path) {
        return ("POST".equals(method) && "/api/auth/logout".equals(path))
            || ("POST".equals(method) && ("/api/auth/oidc/step-up/start".equals(path)
                || "/api/auth/oidc/step-up/handoff".equals(path)))
            || ("POST".equals(method) && ("/api/identity/mfa-recoveries".equals(path)
                || "/api/identity/invitations".equals(path)))
            || ("GET".equals(method) && ("/api/auth/csrf".equals(path)
                || "/api/user/info".equals(path)
                || "/api/auth/codes".equals(path)
                || "/api/menu/all".equals(path)
                || "/api/identity/members".equals(path)
                || "/api/identity/invitation-roles".equals(path)));
    }
}
