package com.niv.payment.permission.application;

import com.niv.payment.permission.datascope.DataScopePlan;
import com.niv.payment.permission.datascope.GrantPredicate;
import com.niv.payment.permission.domain.AuthorizationSubject;
import com.niv.payment.permission.domain.GrantSnapshot;
import com.niv.payment.permission.domain.PermissionCode;

import java.util.List;
import java.util.Objects;

public final class DefaultDataScopePlanner {
    private final PermissionGrantLoader grantLoader;

    public DefaultDataScopePlanner(PermissionGrantLoader grantLoader) {
        this.grantLoader = Objects.requireNonNull(grantLoader, "grantLoader");
    }

    public DataScopePlan plan(AuthorizationSubject subject, PermissionCode permission) {
        GrantSnapshot snapshot = grantLoader.load(subject);
        if (snapshot.tenantId() != subject.tenantId()
            || snapshot.membershipId() != subject.membershipId()
            || snapshot.permissionVersion() != subject.permissionVersion()) {
            throw new IllegalStateException("Cannot plan data scope from a stale permission snapshot");
        }

        List<GrantPredicate> predicates = snapshot.grants().stream()
            .filter(grant -> grant.active() && grant.permission().equals(permission))
            .filter(grant -> !grant.needsStepUp() || subject.stepUpVerified())
            .map(grant -> new GrantPredicate(grant.id(), grant.scopes()))
            .toList();
        return new DataScopePlan(subject.tenantId(), subject.membershipId(), permission,
            snapshot.permissionVersion(), predicates);
    }
}
