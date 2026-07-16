package com.niv.payment.permission;

import com.niv.payment.permission.application.DefaultDataScopePlanner;
import com.niv.payment.permission.application.PermissionGrantLoader;
import com.niv.payment.permission.datascope.DataScopePlan;
import com.niv.payment.permission.datascope.StructuredPredicateCompiler;
import com.niv.payment.permission.datascope.WhitelistedColumns;
import com.niv.payment.permission.domain.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataScopePlannerTest {

    @Test
    void keepsEachGrantTupleSeparateWhenCompilingAListPredicate() {
        PermissionCode permission = PermissionCode.of("order:view");
        PermissionGrant first = grant(1L, permission, "M1", "PK");
        PermissionGrant second = grant(2L, permission, "M2", "BR");
        AuthorizationSubject subject = new AuthorizationSubject(1L, 2L, 3L, 4L, 5L, 6L, false);
        PermissionGrantLoader loader = ignored -> new GrantSnapshot(2L, 3L, 5L, List.of(first, second));
        DefaultDataScopePlanner planner = new DefaultDataScopePlanner(loader);

        DataScopePlan plan = planner.plan(subject, permission);
        WhitelistedColumns columns = new WhitelistedColumns("o.tenant_id", Map.of(
            ScopeDimension.MERCHANT, "o.merchant_id",
            ScopeDimension.MARKET, "o.market_code"));
        var predicate = new StructuredPredicateCompiler().compile(subject, plan, columns);

        assertEquals(2, plan.grantPredicates().size());
        assertTrue(predicate.sql().contains(" OR "));
        assertEquals(List.of(3L, "M1", "PK", "M2", "BR"), predicate.parameters());
    }

    private static PermissionGrant grant(long id, PermissionCode code, String merchant, String market) {
        return new PermissionGrant(id, id + 100, code, RiskLevel.NORMAL,
            Set.of(ScopeDimension.MERCHANT, ScopeDimension.MARKET),
            List.of(
                new DimensionScope(ScopeDimension.MERCHANT, ScopeMode.SPECIFIED, Set.of(merchant)),
                new DimensionScope(ScopeDimension.MARKET, ScopeMode.SPECIFIED, Set.of(market))),
            false, false, true);
    }
}
