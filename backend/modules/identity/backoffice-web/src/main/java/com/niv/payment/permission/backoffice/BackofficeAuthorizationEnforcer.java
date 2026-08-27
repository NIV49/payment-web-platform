package com.niv.payment.permission.backoffice;

import com.niv.payment.permission.application.DefaultAuthorizationService;
import com.niv.payment.permission.domain.AuthorizationDecision;
import com.niv.payment.permission.domain.AuthorizationRequest;
import com.niv.payment.permission.domain.AuthorizationSubject;
import com.niv.payment.permission.domain.DecisionReason;
import com.niv.payment.permission.domain.PermissionCode;
import com.niv.payment.permission.domain.ResourceContext;
import com.niv.payment.permission.security.InvalidSessionException;

final class BackofficeAuthorizationEnforcer {
    private final DefaultAuthorizationService authorization;

    BackofficeAuthorizationEnforcer(DefaultAuthorizationService authorization) {
        this.authorization = authorization;
    }

    void requireTenantPermission(AuthorizationSubject subject, String permissionCode) {
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
        throw new BackofficeAccessDeniedException();
    }
}
