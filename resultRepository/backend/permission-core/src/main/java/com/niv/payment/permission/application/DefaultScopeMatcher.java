package com.niv.payment.permission.application;

import com.niv.payment.permission.domain.AuthorizationSubject;
import com.niv.payment.permission.domain.DimensionScope;
import com.niv.payment.permission.domain.ResourceContext;
import com.niv.payment.permission.port.DepartmentHierarchyPort;
import com.niv.payment.permission.port.RelationshipScopePort;

import java.util.Objects;

public final class DefaultScopeMatcher {
    private final DepartmentHierarchyPort departmentHierarchy;
    private final RelationshipScopePort relationshipScope;

    public DefaultScopeMatcher(DepartmentHierarchyPort departmentHierarchy,
                               RelationshipScopePort relationshipScope) {
        this.departmentHierarchy = Objects.requireNonNull(departmentHierarchy, "departmentHierarchy");
        this.relationshipScope = Objects.requireNonNull(relationshipScope, "relationshipScope");
    }

    public boolean matches(AuthorizationSubject subject, DimensionScope scope, ResourceContext resource) {
        return switch (scope.mode()) {
            case TENANT_ALL -> true;
            case SELF -> matchesSelf(subject, scope, resource);
            case DEPARTMENT -> resource.departmentId() != null
                && resource.departmentId().equals(subject.departmentId());
            case DEPARTMENT_AND_CHILDREN -> subject.departmentId() != null
                && resource.departmentId() != null
                && (resource.departmentId().equals(subject.departmentId())
                    || departmentHierarchy.contains(subject.departmentId(), resource.departmentId()));
            case ASSIGNED, SPECIFIED -> {
                String resourceValue = resource.valueOf(scope.dimension());
                yield resourceValue != null && scope.targets().contains(resourceValue);
            }
            case RELATION_CURRENT, RELATION_AT_EVENT -> relationshipScope.matches(subject, scope, resource);
        };
    }

    private static boolean matchesSelf(AuthorizationSubject subject,
                                       DimensionScope scope,
                                       ResourceContext resource) {
        return switch (scope.dimension()) {
            case OWNER -> resource.ownerMembershipId() != null
                && resource.ownerMembershipId() == subject.membershipId();
            case DEPARTMENT -> resource.departmentId() != null
                && resource.departmentId().equals(subject.departmentId());
            default -> false;
        };
    }
}
