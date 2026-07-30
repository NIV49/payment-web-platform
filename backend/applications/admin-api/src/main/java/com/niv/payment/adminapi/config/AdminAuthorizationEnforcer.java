package com.niv.payment.adminapi.config;

import com.niv.payment.adminapi.web.AccessDeniedException;
import com.niv.payment.permission.application.DefaultAuthorizationService;
import com.niv.payment.permission.domain.AuthorizationDecision;
import com.niv.payment.permission.domain.AuthorizationRequest;
import com.niv.payment.permission.domain.AuthorizationSubject;
import com.niv.payment.permission.domain.DecisionReason;
import com.niv.payment.permission.domain.PermissionCode;
import com.niv.payment.permission.domain.ResourceContext;
import com.niv.payment.permission.security.InvalidSessionException;
import org.springframework.stereotype.Component;

/** HTTP policy-enforcement point for administration resources owned by the active tenant workspace. */
@Component
public final class AdminAuthorizationEnforcer {
    private final DefaultAuthorizationService authorization;

    public AdminAuthorizationEnforcer(DefaultAuthorizationService authorization) {
        this.authorization = authorization;
    }

    public void requireTenantPermission(AuthorizationSubject subject, String permissionCode) {
        ResourceContext resource = new ResourceContext(subject.tenantId(), null, null,
            null, null, null, null);
        AuthorizationDecision decision = authorization.authorize(new AuthorizationRequest(
            subject, PermissionCode.of(permissionCode), resource, null));
        if (decision.allowed()) {
            return;
        }
        if (decision.reason() == DecisionReason.PERMISSION_VERSION_STALE) {
            throw new InvalidSessionException("Session permission version is stale");
        }
        throw new AccessDeniedException();
    }
}
