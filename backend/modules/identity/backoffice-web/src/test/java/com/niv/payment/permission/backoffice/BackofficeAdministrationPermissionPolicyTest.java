package com.niv.payment.permission.backoffice;

import org.junit.jupiter.api.Test;
import com.niv.payment.permission.domain.AccountDomain;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BackofficeAdministrationPermissionPolicyTest {
    private final BackofficeAdministrationPermissionPolicy policy =
        new BackofficeAdministrationPermissionPolicy(AccountDomain.MERCHANT);

    @Test
    void mapsSameTenantUserAndRoleAdministrationExactly() {
        assertThat(policy.requiredPermissions("GET", "/api/system/user/list"))
            .isEqualTo(List.of("user:view"));
        assertThat(policy.requiredPermissions("POST", "/api/system/user"))
            .isEqualTo(List.of("user:create", "user:assign-role"));
        assertThat(policy.requiredPermissions("PUT", "/api/system/user/42"))
            .isEqualTo(List.of("user:update", "user:disable", "user:assign-role"));
        assertThat(policy.requiredPermissions("PUT", "/api/system/user/42/roles"))
            .isEqualTo(List.of("user:assign-role"));
        assertThat(policy.requiredPermissions("PATCH", "/api/system/user/42/status"))
            .isEqualTo(List.of("user:disable"));
        assertThat(policy.requiredPermissions("DELETE", "/api/system/user/42"))
            .isEqualTo(List.of("user:delete"));
        assertThat(policy.requiredPermissions("GET", "/api/system/role/list"))
            .isEqualTo(List.of("role:view"));
        assertThat(policy.requiredPermissions("GET", "/api/system/role/42/members"))
            .isEqualTo(List.of("user:view", "role:view"));
        assertThat(policy.requiredPermissions("PATCH", "/api/system/role/42/members"))
            .isEqualTo(List.of("user:assign-role", "role:view"));
        assertThat(policy.requiredPermissions(
            "POST", "/api/v1/iam/roles/configuration"))
            .isEqualTo(List.of("role:view", "role:create", "menu:view", "role:grant-update"));
    }

    @Test
    void exposesOnlyReadDependenciesForDepartmentAndMenuSelectors() {
        assertThat(policy.requiredPermissions("GET", "/api/system/dept/list"))
            .isEqualTo(List.of("department:view"));
        assertThat(policy.requiredPermissions("GET", "/api/system/menu/list"))
            .isEqualTo(List.of("menu:view"));

        assertThatThrownBy(() -> policy.requiredPermissions("POST", "/api/system/dept"))
            .isInstanceOf(BackofficeAccessDeniedException.class);
        assertThatThrownBy(() -> policy.requiredPermissions("POST", "/api/system/menu"))
            .isInstanceOf(BackofficeAccessDeniedException.class);
    }

    @Test
    void rejectsPlatformCrossDomainManagementAndMalformedItemPaths() {
        assertThatThrownBy(() -> policy.requiredPermissions(
            "GET", "/api/platform/user-directory"))
            .isInstanceOf(BackofficeAccessDeniedException.class);
        assertThatThrownBy(() -> policy.requiredPermissions(
            "DELETE", "/api/system/user/not-a-number"))
            .isInstanceOf(BackofficeAccessDeniedException.class);
    }

    @Test
    void exposesOnlySharedDictionaryReads() {
        assertThat(policy.requiredPermissions("GET", "/api/system/dictionary-types/options"))
            .containsExactly("dictionary-data:view");
        assertThat(policy.requiredPermissions("GET", "/api/system/dictionary-data"))
            .containsExactly("dictionary-data:view");
        assertThat(policy.requiredPermissions("POST", "/api/dict/queryBatch"))
            .containsExactly("dictionary-data:view");

        assertThatThrownBy(() -> policy.requiredPermissions("GET", "/api/system/dictionaries"))
            .isInstanceOf(BackofficeAccessDeniedException.class);
        assertThatThrownBy(() -> policy.requiredPermissions("POST", "/api/system/dictionary-data"))
            .isInstanceOf(BackofficeAccessDeniedException.class);
        assertThatThrownBy(() -> policy.requiredPermissions("PUT", "/api/system/dictionary-data/7"))
            .isInstanceOf(BackofficeAccessDeniedException.class);
        assertThatThrownBy(() -> policy.requiredPermissions("DELETE", "/api/system/dictionary-data/7"))
            .isInstanceOf(BackofficeAccessDeniedException.class);
    }

    @Test
    void merchantExposesOnlySelfServiceAndAgentExposesNoMerchantSurface() {
        assertThat(policy.requiredPermissions("GET", "/api/merchant/application"))
            .containsExactly("merchant:self-view");
        assertThat(policy.requiredPermissions("POST", "/api/merchant/application/submissions"))
            .isEmpty();

        var agent = new BackofficeAdministrationPermissionPolicy(AccountDomain.AGENT);
        assertThatThrownBy(() -> agent.requiredPermissions("GET", "/api/merchant/application"))
            .isInstanceOf(BackofficeAccessDeniedException.class);
        assertThatThrownBy(() -> agent.requiredPermissions(
            "POST", "/api/merchant/application/submissions"))
            .isInstanceOf(BackofficeAccessDeniedException.class);
    }
}
