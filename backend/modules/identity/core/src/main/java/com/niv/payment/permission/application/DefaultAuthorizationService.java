package com.niv.payment.permission.application;

import com.niv.payment.permission.domain.AuthorizationDecision;
import com.niv.payment.permission.domain.AuthorizationRequest;
import com.niv.payment.permission.domain.DecisionReason;
import com.niv.payment.permission.domain.GrantSnapshot;
import com.niv.payment.permission.domain.PermissionGrant;

import java.util.Objects;

public final class DefaultAuthorizationService {
    private final PermissionGrantLoader grantLoader;
    private final DefaultScopeMatcher scopeMatcher;

    public DefaultAuthorizationService(PermissionGrantLoader grantLoader, DefaultScopeMatcher scopeMatcher) {
        this.grantLoader = Objects.requireNonNull(grantLoader, "grantLoader");
        this.scopeMatcher = Objects.requireNonNull(scopeMatcher, "scopeMatcher");
    }

    public AuthorizationDecision authorize(AuthorizationRequest request) {
        if (request.subject().tenantId() != request.resource().tenantId()) {
            return AuthorizationDecision.deny(DecisionReason.TENANT_MISMATCH);
        }

        GrantSnapshot snapshot = grantLoader.load(request.subject());
        if (snapshot.membershipId() != request.subject().membershipId()
            || snapshot.tenantId() != request.subject().tenantId()
            || snapshot.permissionVersion() != request.subject().permissionVersion()) {
            return AuthorizationDecision.deny(DecisionReason.PERMISSION_VERSION_STALE);
        }

        boolean foundPermission = false;
        boolean foundMatchingScope = false;
        for (PermissionGrant grant : snapshot.grants()) {
            if (!grant.active() || !grant.permission().equals(request.permission())) {
                continue;
            }
            foundPermission = true;
            if (!grant.scopes().stream().allMatch(scope ->
                scopeMatcher.matches(request.subject(), scope, request.resource()))) {
                continue;
            }
            foundMatchingScope = true;
            if (grant.needsStepUp() && !request.subject().stepUpVerified()) {
                continue;
            }
            if (grant.requiresApproval()) {
                if (request.initiatorMembershipId() == null) {
                    return AuthorizationDecision.deny(DecisionReason.APPROVAL_CONTEXT_REQUIRED);
                }
                if (request.initiatorMembershipId() == request.subject().membershipId()) {
                    return AuthorizationDecision.deny(DecisionReason.SEPARATION_OF_DUTY);
                }
            }
            return AuthorizationDecision.allow(grant.id());
        }

        if (!foundPermission) {
            return AuthorizationDecision.deny(DecisionReason.PERMISSION_DENIED);
        }
        if (!foundMatchingScope) {
            return AuthorizationDecision.deny(DecisionReason.SCOPE_DENIED);
        }
        return AuthorizationDecision.deny(DecisionReason.STEP_UP_REQUIRED);
    }
}
