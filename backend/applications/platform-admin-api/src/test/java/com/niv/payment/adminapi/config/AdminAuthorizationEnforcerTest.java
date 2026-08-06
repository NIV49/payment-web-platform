package com.niv.payment.adminapi.config;

import com.niv.payment.adminapi.web.AccessDeniedException;
import com.niv.payment.permission.application.DefaultAuthorizationService;
import com.niv.payment.permission.application.DefaultScopeMatcher;
import com.niv.payment.permission.application.PermissionGrantLoader;
import com.niv.payment.permission.domain.AuthorizationSubject;
import com.niv.payment.permission.domain.DimensionScope;
import com.niv.payment.permission.domain.GrantSnapshot;
import com.niv.payment.permission.domain.PermissionCode;
import com.niv.payment.permission.domain.PermissionGrant;
import com.niv.payment.permission.domain.RiskLevel;
import com.niv.payment.permission.domain.ScopeDimension;
import com.niv.payment.permission.domain.ScopeMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdminAuthorizationEnforcerTest {
    private static final PermissionCode USER_VIEW = PermissionCode.of("user:view");
    private static final AuthorizationSubject SUBJECT =
        new AuthorizationSubject(100L, 200L, 300L, 400L, 7L, 3L, false);

    @ParameterizedTest
    @EnumSource(value = ScopeMode.class, names = {
        "SELF", "DEPARTMENT", "DEPARTMENT_AND_CHILDREN", "SPECIFIED"
    })
    void rejectsDepartmentScopedGrantForTenantWideAdministration(ScopeMode departmentMode) {
        AdminAuthorizationEnforcer enforcer = enforcerWith(departmentScopedGrant(departmentMode));

        assertThatThrownBy(() -> enforcer.requireTenantPermission(SUBJECT, USER_VIEW.value()))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void allowsTenantWideGrantForTenantWideAdministration() {
        AdminAuthorizationEnforcer enforcer = enforcerWith(tenantWideGrant());

        assertThatCode(() -> enforcer.requireTenantPermission(SUBJECT, USER_VIEW.value()))
            .doesNotThrowAnyException();
    }

    private static AdminAuthorizationEnforcer enforcerWith(PermissionGrant grant) {
        PermissionGrantLoader loader = subject -> new GrantSnapshot(
            subject.membershipId(), subject.tenantId(), subject.permissionVersion(), List.of(grant));
        DefaultScopeMatcher matcher = new DefaultScopeMatcher(
            (ancestor, child) -> false,
            (subject, scope, resource) -> false);
        return new AdminAuthorizationEnforcer(new DefaultAuthorizationService(loader, matcher));
    }

    private static PermissionGrant departmentScopedGrant(ScopeMode departmentMode) {
        Set<String> targets = departmentMode == ScopeMode.SPECIFIED ? Set.of("400") : Set.of();
        return new PermissionGrant(1L, 10L, USER_VIEW, RiskLevel.NORMAL,
            Set.of(ScopeDimension.TENANT),
            List.of(
                new DimensionScope(ScopeDimension.TENANT, ScopeMode.TENANT_ALL, Set.of()),
                new DimensionScope(ScopeDimension.DEPARTMENT, departmentMode, targets)),
            false, false, true);
    }

    private static PermissionGrant tenantWideGrant() {
        return new PermissionGrant(1L, 10L, USER_VIEW, RiskLevel.NORMAL,
            Set.of(ScopeDimension.TENANT),
            List.of(new DimensionScope(ScopeDimension.TENANT, ScopeMode.TENANT_ALL, Set.of())),
            false, false, true);
    }
}
