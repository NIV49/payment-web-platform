package com.niv.payment.permission;

import com.niv.payment.permission.application.DefaultAuthorizationService;
import com.niv.payment.permission.application.DefaultScopeMatcher;
import com.niv.payment.permission.datascope.DataScopePlan;
import com.niv.payment.permission.datascope.StructuredPredicateCompiler;
import com.niv.payment.permission.datascope.WhitelistedColumns;
import com.niv.payment.permission.domain.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AuthorizationPolicyHardeningTest {

    @Test
    void stalePermissionVersionIsDenied() {
        PermissionGrant grant = new PermissionGrant(1L, 2L, PermissionCode.of("order:view"), RiskLevel.NORMAL,
            Set.of(), List.of(), false, false, true);
        var service = new DefaultAuthorizationService(
            ignored -> new GrantSnapshot(20L, 30L, 8L, List.of(grant)),
            new DefaultScopeMatcher((ancestor, child) -> false, (subject, scope, resource) -> false));
        AuthorizationSubject subject = new AuthorizationSubject(10L, 20L, 30L, 40L, 7L, 1L, false);

        AuthorizationDecision decision = service.authorize(new AuthorizationRequest(subject,
            PermissionCode.of("order:view"), new ResourceContext(30L, 20L, null, null, null, null, null), null));

        assertFalse(decision.allowed());
        assertEquals(DecisionReason.PERMISSION_VERSION_STALE, decision.reason());
    }

    @Test
    void wildcardPermissionAndEmptySpecifiedScopeAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> PermissionCode.of("*:*:*"));
        assertThrows(IllegalArgumentException.class, () -> PermissionCode.of("system:user:create"));
        assertThrows(IllegalArgumentException.class, () ->
            new DimensionScope(ScopeDimension.MERCHANT, ScopeMode.SPECIFIED, Set.of()));
    }

    @Test
    void noMatchingGrantCompilesToTenantBoundDenyPredicate() {
        AuthorizationSubject subject = new AuthorizationSubject(10L, 20L, 30L, 40L, 1L, 1L, false);
        DataScopePlan plan = new DataScopePlan(30L, 20L, PermissionCode.of("order:view"), 1L, List.of());

        var predicate = new StructuredPredicateCompiler().compile(subject, plan,
            new WhitelistedColumns("o.tenant_id", Map.of()));

        assertEquals("o.tenant_id = ? AND 1 = 0", predicate.sql());
        assertEquals(List.of(30L), predicate.parameters());
    }

    @Test
    void dynamicSqlColumnNamesAreRejected() {
        assertThrows(IllegalArgumentException.class, () ->
            new WhitelistedColumns("o.tenant_id OR 1=1", Map.of()));
    }
}
