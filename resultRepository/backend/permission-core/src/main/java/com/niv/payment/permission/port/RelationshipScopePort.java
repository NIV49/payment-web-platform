package com.niv.payment.permission.port;

import com.niv.payment.permission.domain.AuthorizationSubject;
import com.niv.payment.permission.domain.DimensionScope;
import com.niv.payment.permission.domain.ResourceContext;

@FunctionalInterface
public interface RelationshipScopePort {
    boolean matches(AuthorizationSubject subject, DimensionScope scope, ResourceContext resource);
}
