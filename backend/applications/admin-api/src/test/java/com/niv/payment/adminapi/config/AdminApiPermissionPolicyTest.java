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
        assertThat(policy.isPublic("GET", "/api/auth/login")).isFalse();
    }

    @Test
    void mapsEveryRegisteredEndpointToItsPermission() {
        assertThat(policy.requiredPermissions("GET", "/api/user/info")).isEmpty();
        assertThat(policy.requiredPermissions("POST", "/api/auth/logout")).isEmpty();
        assertThat(policy.requiredPermissions("GET", "/api/system/user/list")).isEqualTo(List.of("user:view"));
        assertThat(policy.requiredPermissions("POST", "/api/system/user")).isEqualTo(List.of("user:create"));
        assertThat(policy.requiredPermissions("PUT", "/api/system/user/100"))
            .isEqualTo(List.of("user:update", "user:disable", "user:assign-role"));
        assertThat(policy.requiredPermissions("PATCH", "/api/system/user/100/status"))
            .isEqualTo(List.of("user:disable"));
        assertThat(policy.requiredPermissions("DELETE", "/api/system/user/100")).isEqualTo(List.of("user:delete"));
        assertThat(policy.requiredPermissions("GET", "/api/system/role/list")).isEqualTo(List.of("role:view"));
        assertThat(policy.requiredPermissions("PATCH", "/api/system/role/2000/status"))
            .isEqualTo(List.of("role:update"));
        assertThat(policy.requiredPermissions("GET", "/api/system/menu/name-exists"))
            .isEqualTo(List.of("menu:view"));
        assertThat(policy.requiredPermissions("DELETE", "/api/system/dept/10"))
            .isEqualTo(List.of("department:manage"));
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
}
