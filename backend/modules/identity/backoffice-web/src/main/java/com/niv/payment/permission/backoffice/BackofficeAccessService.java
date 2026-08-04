package com.niv.payment.permission.backoffice;

import com.niv.payment.permission.port.IdentityQueryPort;
import com.niv.payment.permission.service.IdentityModels;

import java.util.List;
import java.util.Objects;

final class BackofficeAccessService {
    private final IdentityQueryPort queries;

    BackofficeAccessService(IdentityQueryPort queries) {
        this.queries = Objects.requireNonNull(queries, "queries");
    }

    IdentityModels.CurrentUser currentUser(long tenantId, long membershipId) {
        return queries.findCurrentUser(tenantId, membershipId)
            .orElseThrow(() -> new IllegalStateException("Current user is unavailable"));
    }

    List<String> permissionCodes(long tenantId, long membershipId) {
        return queries.findPermissionCodes(tenantId, membershipId);
    }

    List<IdentityModels.Menu> menus(long tenantId, long membershipId) {
        return queries.findAccessibleMenus(tenantId, membershipId);
    }
}
