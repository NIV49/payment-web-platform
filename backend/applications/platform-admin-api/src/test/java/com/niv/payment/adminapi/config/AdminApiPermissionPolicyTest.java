package com.niv.payment.adminapi.config;

import com.niv.payment.adminapi.web.AccessDeniedException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdminApiPermissionPolicyTest {
    private final AdminApiPermissionPolicy policy = new AdminApiPermissionPolicy();

    @Test
    void exposesOnlyThePostLoginEndpointWithoutASession() {
        assertThat(policy.isPublic("POST", "/api/auth/login")).isTrue();
        assertThat(policy.isPublic("GET", "/api/auth/oidc/start")).isTrue();
        assertThat(policy.isPublic("GET", "/api/auth/oidc/callback")).isTrue();
        assertThat(policy.isPublic("POST", "/api/auth/oidc/handoff")).isTrue();
        assertThat(policy.isPublic("POST", "/api/auth/oidc/backchannel-logout")).isTrue();
        assertThat(policy.isPublic("GET", "/api/health")).isTrue();
        assertThat(policy.isPublic("GET", "/api/auth/login")).isFalse();
    }

    @Test
    void mapsEveryRegisteredEndpointToItsPermission() {
        assertThat(policy.requiredPermissions("GET", "/api/user/info")).isEmpty();
        assertThat(policy.requiredPermissions("POST", "/api/auth/logout")).isEmpty();
        assertThat(policy.requiredPermissions("POST", "/api/auth/oidc/step-up/start")).isEmpty();
        assertThat(policy.requiredPermissions("POST", "/api/auth/oidc/step-up/handoff")).isEmpty();
        assertThat(policy.requiredPermissions("GET", "/api/auth/csrf")).isEmpty();
        assertThat(policy.requiredPermissions("GET", "/api/identity/members")).isEmpty();
        assertThat(policy.requiredPermissions("GET", "/api/identity/invitation-roles")).isEmpty();
        assertThat(policy.requiredPermissions("POST", "/api/identity/invitations")).isEmpty();
        assertThat(policy.requiredPermissions("POST", "/api/identity/tenant-bootstraps")).isEmpty();
        assertThat(policy.requiredPermissions("POST", "/api/identity/mfa-recoveries")).isEmpty();
        assertThat(policy.requiredPermissions("GET", "/api/system/user/list")).isEqualTo(List.of("user:view"));
        assertThat(policy.requiredPermissions("POST", "/api/system/user")).isEqualTo(List.of("user:create"));
        assertThat(policy.requiredPermissions("PUT", "/api/system/user/100"))
            .isEqualTo(List.of("user:update", "user:disable", "user:assign-role"));
        assertThat(policy.requiredPermissions("PUT", "/api/system/user/100/roles"))
            .isEqualTo(List.of("user:assign-role"));
        assertThat(policy.requiredPermissions("PATCH", "/api/system/user/100/status"))
            .isEqualTo(List.of("user:disable"));
        assertThat(policy.requiredPermissions("POST", "/api/system/user/100/password/reset"))
            .isEqualTo(List.of("user:update"));
        assertThat(policy.requiredPermissions("DELETE", "/api/system/user/100")).isEqualTo(List.of("user:delete"));
        assertThat(policy.requiredPermissions("GET", "/api/platform/user-directory"))
            .isEqualTo(List.of("user:view"));
        assertThat(policy.requiredPermissions("GET", "/api/platform/role-directory"))
            .isEqualTo(List.of("role:view"));
        assertThat(policy.requiredPermissions("GET", "/api/platform/menu-directory"))
            .isEqualTo(List.of("menu:view"));
        assertThat(policy.requiredPermissions("GET", "/api/platform/tenant-options"))
            .isEqualTo(List.of("user:view"));
        assertThat(policy.requiredPermissions("POST", "/api/platform/tenant-administrators"))
            .isEqualTo(List.of("user:create"));
        assertThat(policy.requiredPermissions("PUT", "/api/platform/tenant-administrators/200"))
            .isEqualTo(List.of("user:update", "user:disable"));
        assertThat(policy.requiredPermissions("POST", "/api/platform/users/200/password/reset"))
            .isEqualTo(List.of("user:update"));
        assertThat(policy.requiredPermissions("GET", "/api/system/role/list")).isEqualTo(List.of("role:view"));
        assertThat(policy.requiredPermissions("GET", "/api/system/role/100/members"))
            .isEqualTo(List.of("user:view", "role:view"));
        assertThat(policy.requiredPermissions("PATCH", "/api/system/role/100/members"))
            .isEqualTo(List.of("user:assign-role", "role:view"));
        assertThat(policy.requiredPermissions("PATCH", "/api/system/role/2000/status"))
            .isEqualTo(List.of("role:update"));
        assertThat(policy.requiredPermissions("GET", "/api/v1/iam/permissions/grantable"))
            .isEqualTo(List.of("role:view", "role:grant-update"));
        assertThat(policy.requiredPermissions("GET", "/api/v1/iam/roles/2001/grants"))
            .isEqualTo(List.of("role:view", "role:grant-update"));
        assertThat(policy.requiredPermissions("PUT", "/api/v1/iam/roles/2001/grants"))
            .isEqualTo(List.of("role:view", "role:grant-update"));
        assertThat(policy.requiredPermissions("PUT", "/api/v1/iam/roles/2001/configuration"))
            .isEqualTo(List.of("role:view", "role:update", "menu:view", "role:grant-update"));
        assertThat(policy.requiredPermissions("POST", "/api/v1/iam/roles/configuration"))
            .isEqualTo(List.of("role:view", "role:create", "menu:view", "role:grant-update"));
        assertThat(policy.requiredPermissions("GET", "/api/system/menu/name-exists"))
            .isEqualTo(List.of("menu:view"));
        assertThat(policy.requiredPermissions("POST", "/api/system/menu"))
            .isEqualTo(List.of("menu:create"));
        assertThat(policy.requiredPermissions("PUT", "/api/system/menu/6001"))
            .isEqualTo(List.of("menu:update"));
        assertThat(policy.requiredPermissions("DELETE", "/api/system/menu/6001"))
            .isEqualTo(List.of("menu:delete"));
        assertThat(policy.requiredPermissions("POST", "/api/system/dept"))
            .isEqualTo(List.of("department:create"));
        assertThat(policy.requiredPermissions("PUT", "/api/system/dept/10"))
            .isEqualTo(List.of("department:update"));
        assertThat(policy.requiredPermissions("DELETE", "/api/system/dept/10"))
            .isEqualTo(List.of("department:delete"));
    }

    @Test
    void deniesUnknownRoutesMethodsAndPrefixLookalikes() {
        assertThatThrownBy(() -> policy.requiredPermissions("GET", "/api/not-registered"))
            .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> policy.requiredPermissions("GET", "/api/system/user-archive"))
            .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> policy.requiredPermissions("PATCH", "/api/system/user/100"))
            .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> policy.requiredPermissions("TRACE", "/api/system/menu/list"))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void mapsPlatformDictionaryAdministrationAndSharedReadsExactly() {
        assertThat(policy.requiredPermissions("GET", "/api/system/dictionaries"))
            .containsExactly("dictionary:view");
        assertThat(policy.requiredPermissions("POST", "/api/system/dictionaries"))
            .containsExactly("dictionary:create");
        assertThat(policy.requiredPermissions("PUT", "/api/system/dictionaries/42"))
            .containsExactly("dictionary:update");
        assertThat(policy.requiredPermissions("DELETE", "/api/system/dictionaries/42"))
            .containsExactly("dictionary:delete");
        assertThat(policy.requiredPermissions("GET", "/api/system/dictionary-types/options"))
            .containsExactly("dictionary:view");
        assertThat(policy.requiredPermissions("GET", "/api/system/dictionary-data"))
            .containsExactly("dictionary:view");
        assertThat(policy.requiredPermissions("POST", "/api/dict/queryBatch"))
            .containsExactly("dictionary-data:view");
        assertThat(policy.requiredPermissions("POST", "/api/system/dictionary-data"))
            .containsExactly("dictionary:update");
        assertThat(policy.requiredPermissions("PUT", "/api/system/dictionary-data/71"))
            .containsExactly("dictionary:update");
        assertThat(policy.requiredPermissions("DELETE", "/api/system/dictionary-data/71"))
            .containsExactly("dictionary:update");
    }

    @Test
    void mapsOnlyTheExactPlatformMerchantControlPlane() {
        assertThat(policy.requiredPermissions("GET", "/api/platform/merchants"))
            .containsExactly("merchant:view");
        assertThat(policy.requiredPermissions("GET", "/api/platform/merchants/42"))
            .containsExactly("merchant:view");
        assertThat(policy.requiredPermissions("PUT", "/api/platform/merchants/42/profile"))
            .containsExactly("merchant:update");
        assertThat(policy.requiredPermissions("POST", "/api/platform/merchants/42/review-decisions"))
            .containsExactly("merchant:review");
        assertThat(policy.requiredPermissions("POST", "/api/platform/merchants/42/disable"))
            .containsExactly("merchant:disable");
        assertThat(policy.requiredPermissions("POST", "/api/platform/merchants/42/enable"))
            .containsExactly("merchant:enable");
        assertThat(policy.requiredPermissions("POST", "/api/platform/merchants/42/terminate"))
            .containsExactly("merchant:terminate");
        assertThat(policy.requiredPermissions("GET", "/api/platform/merchants/abc"))
            .containsExactly("merchant:view");
        assertThat(policy.requiredPermissions("GET", "/api/platform/merchants/0"))
            .containsExactly("merchant:view");
        assertThat(policy.requiredPermissions("PUT", "/api/platform/merchants/0/profile"))
            .containsExactly("merchant:update");
        assertThat(policy.requiredPermissions(
            "GET", "/api/platform/merchants/9223372036854775808"))
            .containsExactly("merchant:view");
        assertThat(policy.requiredPermissions(
            "POST", "/api/platform/merchants/abc/review-decisions"))
            .containsExactly("merchant:review");
        assertThat(policy.requiredPermissions("POST", "/api/platform/merchants/0/disable"))
            .containsExactly("merchant:disable");
        assertThat(policy.requiredPermissions(
            "POST", "/api/platform/merchants/9223372036854775808/enable"))
            .containsExactly("merchant:enable");
        assertThat(policy.requiredPermissions("POST", "/api/platform/merchants/abc/terminate"))
            .containsExactly("merchant:terminate");
        assertThatThrownBy(() -> policy.requiredPermissions(
            "GET", "/api/platform/merchants/42/extra"))
            .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> policy.requiredPermissions(
            "POST", "/api/platform/merchants/42/unknown"))
            .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> policy.requiredPermissions("GET", "/api/merchant/application"))
            .isInstanceOf(AccessDeniedException.class);
    }
}
