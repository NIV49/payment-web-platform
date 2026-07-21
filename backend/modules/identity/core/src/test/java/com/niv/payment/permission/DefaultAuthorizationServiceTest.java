package com.niv.payment.permission;

import com.niv.payment.permission.application.DefaultAuthorizationService;
import com.niv.payment.permission.application.DefaultScopeMatcher;
import com.niv.payment.permission.application.PermissionGrantLoader;
import com.niv.payment.permission.domain.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DefaultAuthorizationServiceTest {

    private static final PermissionCode PAYOUT_APPROVE = PermissionCode.of("payout:approve");

    @Test
    void allowsWhenOneGrantCoversTheWholeResourceTuple() {
        PermissionGrant grant = grant(1L, PAYOUT_APPROVE, RiskLevel.FUND,
            specified(ScopeDimension.MERCHANT, "M1"),
            specified(ScopeDimension.MARKET, "PK"));

        var service = serviceWith(grant);
        AuthorizationDecision decision = service.authorize(request(subject(true), "M1", "PK", null));

        assertTrue(decision.allowed());
        assertEquals(1L, decision.matchedGrantId());
    }

    @Test
    void deniesCrossGrantDimensionSplicing() {
        PermissionGrant pakistanForMerchantOne = grant(1L, PAYOUT_APPROVE, RiskLevel.FUND,
            specified(ScopeDimension.MERCHANT, "M1"),
            specified(ScopeDimension.MARKET, "PK"));
        PermissionGrant brazilForMerchantTwo = grant(2L, PAYOUT_APPROVE, RiskLevel.FUND,
            specified(ScopeDimension.MERCHANT, "M2"),
            specified(ScopeDimension.MARKET, "BR"));

        var service = serviceWith(pakistanForMerchantOne, brazilForMerchantTwo);
        AuthorizationDecision decision = service.authorize(request(subject(true), "M1", "BR", null));

        assertFalse(decision.allowed());
        assertEquals(DecisionReason.SCOPE_DENIED, decision.reason());
    }

    @Test
    void deniesWhenAnUnrelatedRoleHasBroadScope() {
        PermissionGrant narrowPayout = grant(1L, PAYOUT_APPROVE, RiskLevel.FUND,
            specified(ScopeDimension.MERCHANT, "M1"),
            specified(ScopeDimension.MARKET, "PK"));
        PermissionGrant broadReport = new PermissionGrant(2L, 20L, PermissionCode.of("report:view"),
            RiskLevel.NORMAL, Set.of(ScopeDimension.TENANT),
            List.of(new DimensionScope(ScopeDimension.TENANT, ScopeMode.TENANT_ALL, Set.of())),
            false, false, true);

        var service = serviceWith(narrowPayout, broadReport);
        AuthorizationDecision decision = service.authorize(request(subject(true), "M2", "PK", null));

        assertFalse(decision.allowed());
        assertEquals(DecisionReason.SCOPE_DENIED, decision.reason());
    }

    @Test
    void fundPermissionRequiresStepUpEvenForAnOtherwiseMatchingGrant() {
        PermissionGrant grant = grant(1L, PAYOUT_APPROVE, RiskLevel.FUND,
            specified(ScopeDimension.MERCHANT, "M1"),
            specified(ScopeDimension.MARKET, "PK"));

        var service = serviceWith(grant);
        AuthorizationDecision decision = service.authorize(request(subject(false), "M1", "PK", null));

        assertFalse(decision.allowed());
        assertEquals(DecisionReason.STEP_UP_REQUIRED, decision.reason());
    }

    @Test
    void approverCannotApproveTheirOwnRequest() {
        PermissionGrant grant = new PermissionGrant(1L, 10L, PAYOUT_APPROVE, RiskLevel.FUND,
            Set.of(ScopeDimension.MERCHANT, ScopeDimension.MARKET),
            List.of(specified(ScopeDimension.MERCHANT, "M1"), specified(ScopeDimension.MARKET, "PK")),
            true, true, true);

        var service = serviceWith(grant);
        AuthorizationDecision decision = service.authorize(request(subject(true), "M1", "PK", 200L));

        assertFalse(decision.allowed());
        assertEquals(DecisionReason.SEPARATION_OF_DUTY, decision.reason());
    }

    @Test
    void callerSuppliedInitiatorDoesNotCountAsTrustedApprovalEvidence() {
        PermissionGrant grant = new PermissionGrant(1L, 10L, PAYOUT_APPROVE, RiskLevel.FUND,
            Set.of(ScopeDimension.MERCHANT, ScopeDimension.MARKET),
            List.of(specified(ScopeDimension.MERCHANT, "M1"), specified(ScopeDimension.MARKET, "PK")),
            true, true, true);

        AuthorizationDecision decision = serviceWith(grant)
            .authorize(request(subject(true), "M1", "PK", 999L));

        assertFalse(decision.allowed());
        assertEquals(DecisionReason.APPROVAL_CONTEXT_REQUIRED, decision.reason());
    }

    @Test
    void blockedApprovalGrantDoesNotHideAnOrdinaryMatchingGrant() {
        PermissionGrant approvalGrant = new PermissionGrant(1L, 10L, PAYOUT_APPROVE, RiskLevel.FUND,
            Set.of(ScopeDimension.MERCHANT, ScopeDimension.MARKET),
            List.of(specified(ScopeDimension.MERCHANT, "M1"), specified(ScopeDimension.MARKET, "PK")),
            true, true, true);
        PermissionGrant ordinaryGrant = new PermissionGrant(2L, 11L, PAYOUT_APPROVE, RiskLevel.FUND,
            Set.of(ScopeDimension.MERCHANT, ScopeDimension.MARKET),
            List.of(specified(ScopeDimension.MERCHANT, "M1"), specified(ScopeDimension.MARKET, "PK")),
            true, false, true);

        AuthorizationDecision decision = serviceWith(approvalGrant, ordinaryGrant)
            .authorize(request(subject(true), "M1", "PK", 999L));

        assertTrue(decision.allowed());
        assertEquals(2L, decision.matchedGrantId());
    }

    @Test
    void tenantMismatchIsDeniedBeforeScopeEvaluation() {
        PermissionGrant grant = grant(1L, PAYOUT_APPROVE, RiskLevel.FUND,
            specified(ScopeDimension.MERCHANT, "M1"),
            specified(ScopeDimension.MARKET, "PK"));
        var service = serviceWith(grant);
        ResourceContext resource = new ResourceContext(999L, 300L, null, null, "M1", "PK", null);

        AuthorizationDecision decision = service.authorize(new AuthorizationRequest(subject(true), PAYOUT_APPROVE,
            resource, null));

        assertFalse(decision.allowed());
        assertEquals(DecisionReason.TENANT_MISMATCH, decision.reason());
    }

    @Test
    void allowsCrossTenantReadOnlyResourceWhenGrantScopeAndBusinessRelationshipBothMatch() {
        PermissionCode orderView = PermissionCode.of("order:view");
        PermissionGrant grant = new PermissionGrant(1L, 10L, orderView, RiskLevel.NORMAL,
            CrossTenantMode.RELATED_PARTY_READ, Set.of(ScopeDimension.MERCHANT, ScopeDimension.MARKET),
            List.of(specified(ScopeDimension.MERCHANT, "M1"), specified(ScopeDimension.MARKET, "PK")),
            false, false, true);
        PermissionGrantLoader loader = subject -> new GrantSnapshot(subject.membershipId(), subject.tenantId(),
            subject.permissionVersion(), List.of(grant));
        DefaultScopeMatcher matcher = new DefaultScopeMatcher((ancestor, child) -> false,
            (subject, scope, resource) -> false);
        var service = new DefaultAuthorizationService(loader, matcher,
            (subject, matchedGrant, resource) -> "M1".equals(resource.merchantRef()));
        ResourceContext merchantOwnedResource = new ResourceContext(
            999L, 300L, null, null, "M1", "PK", null);

        AuthorizationDecision decision = service.authorize(new AuthorizationRequest(
            subject(true), orderView, merchantOwnedResource, null));

        assertTrue(decision.allowed());
        assertEquals(1L, decision.matchedGrantId());
    }

    @Test
    void relatedPartyReadMetadataCannotRepresentCrossTenantMutationActions() {
        for (PermissionCode mutation : List.of(
            PermissionCode.of("merchant:update"),
            PermissionCode.of("order:update"))) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                new PermissionGrant(1L, 10L, mutation, RiskLevel.NORMAL,
                    CrossTenantMode.RELATED_PARTY_READ, Set.of(ScopeDimension.MERCHANT),
                    List.of(specified(ScopeDimension.MERCHANT, "M1")),
                    false, false, true));
            assertTrue(error.getMessage().contains("read-only"), mutation.toString());
        }
    }

    @Test
    void businessRelationshipAloneCannotCreateCrossTenantPermission() {
        PermissionCode orderView = PermissionCode.of("order:view");
        PermissionGrant tenantBoundGrant = new PermissionGrant(1L, 10L, orderView, RiskLevel.NORMAL,
            Set.of(ScopeDimension.MERCHANT),
            List.of(specified(ScopeDimension.MERCHANT, "M1")),
            false, false, true);
        PermissionGrantLoader loader = subject -> new GrantSnapshot(subject.membershipId(), subject.tenantId(),
            subject.permissionVersion(), List.of(tenantBoundGrant));
        DefaultScopeMatcher matcher = new DefaultScopeMatcher((ancestor, child) -> false,
            (subject, scope, resource) -> false);
        var service = new DefaultAuthorizationService(loader, matcher,
            (subject, matchedGrant, resource) -> true);

        AuthorizationDecision decision = service.authorize(new AuthorizationRequest(subject(true), orderView,
            new ResourceContext(999L, 300L, null, null, "M1", "PK", null), null));

        assertFalse(decision.allowed());
        assertEquals(DecisionReason.TENANT_MISMATCH, decision.reason());
    }

    @Test
    void fundPermissionsRemainTenantBoundEvenWhenBusinessRelationshipMatches() {
        PermissionGrant grant = grant(1L, PAYOUT_APPROVE, RiskLevel.FUND,
            specified(ScopeDimension.MERCHANT, "M1"),
            specified(ScopeDimension.MARKET, "PK"));
        PermissionGrantLoader loader = subject -> new GrantSnapshot(subject.membershipId(), subject.tenantId(),
            subject.permissionVersion(), List.of(grant));
        DefaultScopeMatcher matcher = new DefaultScopeMatcher((ancestor, child) -> false,
            (subject, scope, resource) -> false);
        var service = new DefaultAuthorizationService(loader, matcher,
            (subject, matchedGrant, resource) -> true);

        AuthorizationDecision decision = service.authorize(new AuthorizationRequest(subject(true), PAYOUT_APPROVE,
            new ResourceContext(999L, 300L, null, null, "M1", "PK", null), null));

        assertFalse(decision.allowed());
        assertEquals(DecisionReason.TENANT_MISMATCH, decision.reason());
    }

    private static DefaultAuthorizationService serviceWith(PermissionGrant... grants) {
        PermissionGrantLoader loader = subject -> new GrantSnapshot(subject.membershipId(), subject.tenantId(),
            subject.permissionVersion(), List.of(grants));
        DefaultScopeMatcher matcher = new DefaultScopeMatcher((ancestor, child) -> false,
            (subject, scope, resource) -> false);
        return new DefaultAuthorizationService(loader, matcher);
    }

    private static PermissionGrant grant(long id,
                                         PermissionCode code,
                                         RiskLevel risk,
                                         DimensionScope... scopes) {
        Set<ScopeDimension> required = Set.of(ScopeDimension.MERCHANT, ScopeDimension.MARKET);
        return new PermissionGrant(id, 10L, code, risk, required, List.of(scopes), risk == RiskLevel.FUND,
            false, true);
    }

    private static DimensionScope specified(ScopeDimension dimension, String... targets) {
        return new DimensionScope(dimension, ScopeMode.SPECIFIED, Set.of(targets));
    }

    private static AuthorizationSubject subject(boolean stepUp) {
        return new AuthorizationSubject(100L, 200L, 300L, 400L, 7L, 3L, stepUp);
    }

    private static AuthorizationRequest request(AuthorizationSubject subject,
                                                String merchant,
                                                String market,
                                                Long initiatorMembershipId) {
        ResourceContext resource = new ResourceContext(300L, 999L, null, null, merchant, market, null);
        return new AuthorizationRequest(subject, PAYOUT_APPROVE, resource, initiatorMembershipId);
    }
}
