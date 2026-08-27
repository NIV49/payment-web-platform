package com.niv.payment.adminapi.config;

import com.niv.payment.adminapi.web.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/** Explicit method/path policy for the browser administration API. Unknown routes fail closed. */
@Component
public final class AdminApiPermissionPolicy {
    private static final Pattern USER_ITEM = Pattern.compile("^/api/system/user/[1-9][0-9]*$");
    private static final Pattern USER_STATUS = Pattern.compile("^/api/system/user/[1-9][0-9]*/status$");
    private static final Pattern USER_ROLES =
        Pattern.compile("^/api/system/user/[1-9][0-9]*/roles$");
    private static final Pattern USER_PASSWORD_RESET =
        Pattern.compile("^/api/system/user/[1-9][0-9]*/password/reset$");
    private static final Pattern ROLE_ITEM = Pattern.compile("^/api/system/role/[1-9][0-9]*$");
    private static final Pattern ROLE_STATUS = Pattern.compile("^/api/system/role/[1-9][0-9]*/status$");
    private static final Pattern ROLE_MEMBERS =
        Pattern.compile("^/api/system/role/[1-9][0-9]*/members$");
    private static final Pattern MENU_ITEM = Pattern.compile("^/api/system/menu/[1-9][0-9]*$");
    private static final Pattern DEPARTMENT_ITEM = Pattern.compile("^/api/system/dept/[1-9][0-9]*$");
    private static final Pattern ROLE_GRANTS = Pattern.compile("^/api/v1/iam/roles/[1-9][0-9]*/grants$");
    private static final Pattern ROLE_CONFIGURATION =
        Pattern.compile("^/api/v1/iam/roles/[1-9][0-9]*/configuration$");
    private static final Pattern TENANT_ADMINISTRATOR =
        Pattern.compile("^/api/platform/tenant-administrators/[1-9][0-9]*$");
    private static final Pattern PLATFORM_USER_PASSWORD_RESET =
        Pattern.compile("^/api/platform/users/[1-9][0-9]*/password/reset$");
    private static final Pattern DICTIONARY_TYPE_ITEM =
        Pattern.compile("^/api/system/dictionaries/[1-9][0-9]*$");
    private static final Pattern DICTIONARY_DATA_ITEM =
        Pattern.compile("^/api/system/dictionary-data/[1-9][0-9]*$");
    private static final Pattern MERCHANT_ITEM =
        Pattern.compile("^/api/platform/merchants/[^/]+$");
    private static final Pattern MERCHANT_REVIEW =
        Pattern.compile("^/api/platform/merchants/[^/]+/review-decisions$");
    private static final Pattern MERCHANT_PROFILE =
        Pattern.compile("^/api/platform/merchants/[^/]+/profile$");
    private static final Pattern MERCHANT_DISABLE =
        Pattern.compile("^/api/platform/merchants/[^/]+/disable$");
    private static final Pattern MERCHANT_ENABLE =
        Pattern.compile("^/api/platform/merchants/[^/]+/enable$");
    private static final Pattern MERCHANT_TERMINATE =
        Pattern.compile("^/api/platform/merchants/[^/]+/terminate$");
    private static final Pattern MERCHANT_AMENDMENT =
        Pattern.compile("^/api/platform/merchants/[1-9][0-9]*/amendments$");
    private static final Pattern MERCHANT_PENDING_AMENDMENT =
        Pattern.compile("^/api/platform/merchants/[1-9][0-9]*/amendments/pending$");
    private static final Pattern MERCHANT_AMENDMENT_REVIEW = Pattern.compile(
        "^/api/platform/merchants/[1-9][0-9]*/amendments/[1-9][0-9]*/review-decisions$");
    private static final Pattern MERCHANT_DOCUMENT = Pattern.compile(
        "^/api/platform/merchants/[1-9][0-9]*/documents/(BRAND_LOGO|BUSINESS_LICENSE|LEGAL_ID_FRONT|LEGAL_ID_BACK|LEGAL_ID_HOLDING)/content$");
    private static final Pattern MERCHANT_DOCUMENT_UPLOAD =
        Pattern.compile("^/api/platform/merchant-document-uploads/[1-9][0-9]*$");
    private static final Pattern MERCHANT_DOCUMENT_UPLOAD_CONTENT = Pattern.compile(
        "^/api/platform/merchant-document-uploads/[1-9][0-9]*/content$");

    public boolean isPublic(String method, String path) {
        return ("POST".equals(method) && "/api/auth/login".equals(path))
            || ("GET".equals(method) && ("/api/auth/oidc/start".equals(path)
                || "/api/auth/oidc/callback".equals(path)))
            || ("POST".equals(method) && "/api/auth/oidc/handoff".equals(path))
            || ("POST".equals(method) && "/api/auth/oidc/backchannel-logout".equals(path))
            || ("GET".equals(method) && "/api/health".equals(path));
    }

    public List<String> requiredPermissions(String method, String path) {
        if (sessionOnly(method, path)) return List.of();

        if ("GET".equals(method) && "/api/system/user/list".equals(path)) return List.of("user:view");
        if ("POST".equals(method) && "/api/system/user".equals(path)) return List.of("user:create");
        if ("PUT".equals(method) && USER_ITEM.matcher(path).matches()) {
            return List.of("user:update", "user:disable", "user:assign-role");
        }
        if ("PATCH".equals(method) && USER_STATUS.matcher(path).matches()) return List.of("user:disable");
        if ("PUT".equals(method) && USER_ROLES.matcher(path).matches()) {
            return List.of("user:assign-role");
        }
        if ("POST".equals(method) && USER_PASSWORD_RESET.matcher(path).matches()) {
            return List.of("user:update");
        }
        if ("DELETE".equals(method) && USER_ITEM.matcher(path).matches()) return List.of("user:delete");

        if ("GET".equals(method) && ("/api/platform/user-directory".equals(path)
            || "/api/platform/tenant-options".equals(path))) return List.of("user:view");
        if ("GET".equals(method) && "/api/platform/role-directory".equals(path)) {
            return List.of("role:view");
        }
        if ("GET".equals(method) && "/api/platform/menu-directory".equals(path)) {
            return List.of("menu:view");
        }
        if ("POST".equals(method) && "/api/platform/tenant-administrators".equals(path)) {
            return List.of("user:create");
        }
        if ("PUT".equals(method) && TENANT_ADMINISTRATOR.matcher(path).matches()) {
            return List.of("user:update", "user:disable");
        }
        if ("POST".equals(method) && PLATFORM_USER_PASSWORD_RESET.matcher(path).matches()) {
            return List.of("user:update");
        }

        if ("GET".equals(method) && "/api/system/role/list".equals(path)) return List.of("role:view");
        if ("GET".equals(method) && ROLE_MEMBERS.matcher(path).matches()) {
            return List.of("user:view", "role:view");
        }
        if ("PATCH".equals(method) && ROLE_MEMBERS.matcher(path).matches()) {
            return List.of("user:assign-role", "role:view");
        }
        if ("POST".equals(method) && "/api/system/role".equals(path)) return List.of("role:create");
        if (("PUT".equals(method) && ROLE_ITEM.matcher(path).matches())
            || ("PATCH".equals(method) && ROLE_STATUS.matcher(path).matches())) {
            return List.of("role:update");
        }
        if ("DELETE".equals(method) && ROLE_ITEM.matcher(path).matches()) return List.of("role:delete");
        if ("GET".equals(method) && "/api/v1/iam/permissions/grantable".equals(path)) {
            return List.of("role:view", "role:grant-update");
        }
        if (("GET".equals(method) || "PUT".equals(method)) && ROLE_GRANTS.matcher(path).matches()) {
            return List.of("role:view", "role:grant-update");
        }
        if ("PUT".equals(method) && ROLE_CONFIGURATION.matcher(path).matches()) {
            return List.of("role:view", "role:update", "menu:view", "role:grant-update");
        }
        if ("POST".equals(method) && "/api/v1/iam/roles/configuration".equals(path)) {
            return List.of("role:view", "role:create", "menu:view", "role:grant-update");
        }

        if ("GET".equals(method) && ("/api/system/menu/list".equals(path)
            || "/api/system/menu/name-exists".equals(path) || "/api/system/menu/path-exists".equals(path))) {
            return List.of("menu:view");
        }
        if ("POST".equals(method) && "/api/system/menu".equals(path)) return List.of("menu:create");
        if ("PUT".equals(method) && MENU_ITEM.matcher(path).matches()) return List.of("menu:update");
        if ("DELETE".equals(method) && MENU_ITEM.matcher(path).matches()) return List.of("menu:delete");

        if ("GET".equals(method) && "/api/system/dept/list".equals(path)) return List.of("department:view");
        if ("POST".equals(method) && "/api/system/dept".equals(path)) return List.of("department:create");
        if ("PUT".equals(method) && DEPARTMENT_ITEM.matcher(path).matches()) return List.of("department:update");
        if ("DELETE".equals(method) && DEPARTMENT_ITEM.matcher(path).matches()) return List.of("department:delete");

        if ("GET".equals(method) && "/api/system/dictionaries".equals(path)) {
            return List.of("dictionary:view");
        }
        if ("POST".equals(method) && "/api/system/dictionaries".equals(path)) {
            return List.of("dictionary:create");
        }
        if ("PUT".equals(method) && DICTIONARY_TYPE_ITEM.matcher(path).matches()) {
            return List.of("dictionary:update");
        }
        if ("DELETE".equals(method) && DICTIONARY_TYPE_ITEM.matcher(path).matches()) {
            return List.of("dictionary:delete");
        }
        if ("GET".equals(method) && ("/api/system/dictionary-types/options".equals(path)
            || "/api/system/dictionary-data".equals(path))) {
            return List.of("dictionary:view");
        }
        if ("POST".equals(method) && "/api/dict/queryBatch".equals(path)) {
            return List.of("dictionary-data:view");
        }
        if ("GET".equals(method) && "/api/platform/merchants".equals(path)) {
            return List.of("merchant:view");
        }
        if ("GET".equals(method)
            && "/api/platform/merchant-onboarding/eligible-tenants".equals(path)) {
            return List.of("merchant:create");
        }
        if ("POST".equals(method) && "/api/platform/merchants".equals(path)) {
            return List.of("merchant:create");
        }
        if ("POST".equals(method) && MERCHANT_AMENDMENT.matcher(path).matches()) {
            return List.of("merchant:amend");
        }
        if ("GET".equals(method) && MERCHANT_PENDING_AMENDMENT.matcher(path).matches()) {
            return List.of("merchant:view");
        }
        if ("POST".equals(method) && MERCHANT_AMENDMENT_REVIEW.matcher(path).matches()) {
            return List.of("merchant:review");
        }
        if ("POST".equals(method) && "/api/platform/merchant-document-uploads".equals(path)) {
            return List.of("merchant:document:upload");
        }
        if (("GET".equals(method) && MERCHANT_DOCUMENT_UPLOAD_CONTENT.matcher(path).matches())
            || ("DELETE".equals(method) && MERCHANT_DOCUMENT_UPLOAD.matcher(path).matches())) {
            return List.of("merchant:document:upload");
        }
        if ("GET".equals(method) && MERCHANT_DOCUMENT.matcher(path).matches()) {
            return List.of("merchant:document:view");
        }
        if ("GET".equals(method) && MERCHANT_ITEM.matcher(path).matches()) {
            return List.of("merchant:view");
        }
        if ("PUT".equals(method) && MERCHANT_PROFILE.matcher(path).matches()) {
            return List.of("merchant:update");
        }
        if ("POST".equals(method) && MERCHANT_REVIEW.matcher(path).matches()) {
            return List.of("merchant:review");
        }
        if ("POST".equals(method) && MERCHANT_DISABLE.matcher(path).matches()) {
            return List.of("merchant:disable");
        }
        if ("POST".equals(method) && MERCHANT_ENABLE.matcher(path).matches()) {
            return List.of("merchant:enable");
        }
        if ("POST".equals(method) && MERCHANT_TERMINATE.matcher(path).matches()) {
            return List.of("merchant:terminate");
        }
        if ("POST".equals(method) && "/api/system/dictionary-data".equals(path)) {
            return List.of("dictionary:update");
        }
        if ("PUT".equals(method) && DICTIONARY_DATA_ITEM.matcher(path).matches()) {
            return List.of("dictionary:update");
        }
        if ("DELETE".equals(method) && DICTIONARY_DATA_ITEM.matcher(path).matches()) {
            return List.of("dictionary:update");
        }

        throw new AccessDeniedException();
    }

    private static boolean sessionOnly(String method, String path) {
        return ("POST".equals(method) && "/api/auth/logout".equals(path))
            || ("POST".equals(method) && ("/api/auth/oidc/step-up/start".equals(path)
                || "/api/auth/oidc/step-up/handoff".equals(path)))
            || ("POST".equals(method) && "/api/identity/mfa-recoveries".equals(path))
            || ("POST".equals(method) && ("/api/identity/invitations".equals(path)
                || "/api/identity/tenant-bootstraps".equals(path)))
            || ("GET".equals(method) && ("/api/auth/csrf".equals(path)
                || "/api/user/info".equals(path)
                || "/api/auth/codes".equals(path) || "/api/menu/all".equals(path)
                || "/api/identity/members".equals(path)
                || "/api/identity/invitation-roles".equals(path)));
    }
}
