package com.niv.payment.adminapi.config;

import com.niv.payment.adminapi.web.AccessDeniedException;
import com.niv.payment.permission.application.DefaultAuthorizationService;
import com.niv.payment.permission.domain.AuthorizationRequest;
import com.niv.payment.permission.domain.AuthorizationSubject;
import com.niv.payment.permission.domain.PermissionCode;
import com.niv.payment.permission.domain.ResourceContext;
import org.springframework.stereotype.Component;

/** HTTP policy-enforcement point for administration resources owned by the active tenant workspace. */
@Component
public final class AdminAuthorizationEnforcer {
    private final DefaultAuthorizationService authorization;

    public AdminAuthorizationEnforcer(DefaultAuthorizationService authorization) {
        this.authorization = authorization;
    }

    public void requireTenantPermission(AuthorizationSubject subject, String permissionCode) {
        ResourceContext resource = new ResourceContext(subject.tenantId(), null, subject.departmentId(),
            null, null, null, null);
        if (!authorization.authorize(new AuthorizationRequest(
            subject, PermissionCode.of(permissionCode), resource, null)).allowed()) {
            throw new AccessDeniedException();
        }
    }
}
