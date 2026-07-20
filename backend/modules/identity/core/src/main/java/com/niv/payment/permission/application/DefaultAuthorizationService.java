package com.niv.payment.permission.application;

import com.niv.payment.permission.domain.AuthorizationDecision;
import com.niv.payment.permission.domain.AuthorizationRequest;
import com.niv.payment.permission.domain.CrossTenantMode;
import com.niv.payment.permission.domain.DecisionReason;
import com.niv.payment.permission.domain.GrantSnapshot;
import com.niv.payment.permission.domain.PermissionGrant;
import com.niv.payment.permission.domain.RiskLevel;
import com.niv.payment.permission.domain.ScopeDimension;
import com.niv.payment.permission.domain.ScopeMode;
import com.niv.payment.permission.port.CrossTenantAccessPort;

import java.util.Objects;

public final class DefaultAuthorizationService {
    private final PermissionGrantLoader grantLoader;
    private final DefaultScopeMatcher scopeMatcher;
    private final CrossTenantAccessPort crossTenantAccess;

    public DefaultAuthorizationService(PermissionGrantLoader grantLoader, DefaultScopeMatcher scopeMatcher) {
        this(grantLoader, scopeMatcher, (subject, grant, resource) -> false);
    }

    public DefaultAuthorizationService(PermissionGrantLoader grantLoader,
                                       DefaultScopeMatcher scopeMatcher,
                                       CrossTenantAccessPort crossTenantAccess) {
        this.grantLoader = Objects.requireNonNull(grantLoader, "grantLoader");
        this.scopeMatcher = Objects.requireNonNull(scopeMatcher, "scopeMatcher");
        this.crossTenantAccess = Objects.requireNonNull(crossTenantAccess, "crossTenantAccess");
    }

    public AuthorizationDecision authorize(AuthorizationRequest request) {
        GrantSnapshot snapshot = grantLoader.load(request.subject());
        if (snapshot.membershipId() != request.subject().membershipId()
            || snapshot.tenantId() != request.subject().tenantId()
            || snapshot.permissionVersion() != request.subject().permissionVersion()) {
            return AuthorizationDecision.deny(DecisionReason.PERMISSION_VERSION_STALE);
        }

        boolean foundPermission = false;
        boolean foundMatchingScope = false;
        boolean crossTenant = request.subject().tenantId() != request.resource().tenantId();
        boolean foundCrossTenantEvidence = false;
        boolean approvalContextRequired = false;
        boolean separationOfDutyViolated = false;
        for (PermissionGrant grant : snapshot.grants()) {
            if (!grant.active() || !grant.permission().equals(request.permission())) {
                continue;
            }
            foundPermission = true;
            if (crossTenant) {
                if (grant.riskLevel() == RiskLevel.FUND
                    || grant.crossTenantMode() != CrossTenantMode.RELATED_PARTY_READ
                    || !hasExplicitBusinessPartyScope(grant)
                    || !crossTenantAccess.allows(request.subject(), grant, request.resource())) {
                    continue;
                }
                foundCrossTenantEvidence = true;
            }
            if (!grant.scopes().stream().allMatch(scope ->
                scopeMatcher.matches(request.subject(), scope, request.resource()))) {
                continue;
            }
            foundMatchingScope = true;
            if (grant.needsStepUp() && !request.subject().stepUpVerified()) {
                continue;
            }
            if (grant.requiresApproval()) {
                // A caller-supplied initiator identifier is not trusted approval evidence.
                // Until an approval workflow provides a verified approval record, every
                // approval-bound grant remains fail-closed. Continue so an independent,
                // ordinary grant can still authorize the same operation.
                separationOfDutyViolated |= request.initiatorMembershipId() != null
                    && request.initiatorMembershipId() == request.subject().membershipId();
                approvalContextRequired = true;
                continue;
            }
            return AuthorizationDecision.allow(grant.id());
        }

        if (!foundPermission) {
            return AuthorizationDecision.deny(DecisionReason.PERMISSION_DENIED);
        }
        if (crossTenant && !foundCrossTenantEvidence) {
            return AuthorizationDecision.deny(DecisionReason.TENANT_MISMATCH);
        }
        if (!foundMatchingScope) {
            return AuthorizationDecision.deny(DecisionReason.SCOPE_DENIED);
        }
        if (separationOfDutyViolated) {
            return AuthorizationDecision.deny(DecisionReason.SEPARATION_OF_DUTY);
        }
        if (approvalContextRequired) {
            return AuthorizationDecision.deny(DecisionReason.APPROVAL_CONTEXT_REQUIRED);
        }
        return AuthorizationDecision.deny(DecisionReason.STEP_UP_REQUIRED);
    }

    private static boolean hasExplicitBusinessPartyScope(PermissionGrant grant) {
        return grant.scopes().stream().anyMatch(scope ->
            (scope.dimension() == ScopeDimension.MERCHANT || scope.dimension() == ScopeDimension.CUSTOMER)
                && scope.mode() != ScopeMode.TENANT_ALL);
    }
}
