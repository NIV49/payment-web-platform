package com.niv.payment.permission.datascope;

import com.niv.payment.permission.domain.AuthorizationSubject;
import com.niv.payment.permission.domain.DimensionScope;
import com.niv.payment.permission.domain.ScopeDimension;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class StructuredPredicateCompiler {

    public SqlPredicate compile(AuthorizationSubject subject,
                                DataScopePlan plan,
                                WhitelistedColumns columns) {
        validateIdentity(subject, plan);
        List<Object> parameters = new ArrayList<>();
        parameters.add(plan.tenantId());

        if (plan.grantPredicates().isEmpty()) {
            return new SqlPredicate(columns.tenantColumn() + " = ? AND 1 = 0", parameters);
        }

        List<String> grantSql = new ArrayList<>();
        for (GrantPredicate grant : plan.grantPredicates()) {
            List<String> dimensions = new ArrayList<>();
            grant.scopes().stream()
                .sorted(Comparator.comparing(scope -> scope.dimension().ordinal()))
                .forEach(scope -> dimensions.add(compileScope(subject, scope, columns, parameters)));
            grantSql.add("(" + (dimensions.isEmpty() ? "1 = 1" : String.join(" AND ", dimensions)) + ")");
        }
        String sql = columns.tenantColumn() + " = ? AND (" + String.join(" OR ", grantSql) + ")";
        return new SqlPredicate(sql, parameters);
    }

    private static String compileScope(AuthorizationSubject subject,
                                       DimensionScope scope,
                                       WhitelistedColumns columns,
                                       List<Object> parameters) {
        return switch (scope.mode()) {
            case TENANT_ALL -> "1 = 1";
            case SELF -> compileSelf(subject, scope.dimension(), columns, parameters);
            case DEPARTMENT -> {
                String column = columns.requireColumn(ScopeDimension.DEPARTMENT);
                parameters.add(requireDepartment(subject));
                yield column + " = ?";
            }
            case SPECIFIED, ASSIGNED -> compileTargets(scope, columns, parameters);
            case DEPARTMENT_AND_CHILDREN, RELATION_CURRENT, RELATION_AT_EVENT ->
                throw new IllegalStateException("Scope mode requires a trusted provider expansion: " + scope.mode());
        };
    }

    private static String compileSelf(AuthorizationSubject subject,
                                      ScopeDimension dimension,
                                      WhitelistedColumns columns,
                                      List<Object> parameters) {
        if (dimension == ScopeDimension.OWNER) {
            parameters.add(subject.membershipId());
            return columns.requireColumn(dimension) + " = ?";
        }
        if (dimension == ScopeDimension.DEPARTMENT) {
            parameters.add(requireDepartment(subject));
            return columns.requireColumn(dimension) + " = ?";
        }
        throw new IllegalStateException("SELF is only valid for OWNER or DEPARTMENT");
    }

    private static long requireDepartment(AuthorizationSubject subject) {
        if (subject.departmentId() == null) {
            throw new IllegalStateException("Department scope cannot be evaluated for a membership without a department");
        }
        return subject.departmentId();
    }

    private static String compileTargets(DimensionScope scope,
                                         WhitelistedColumns columns,
                                         List<Object> parameters) {
        if (scope.targets().isEmpty()) {
            return "1 = 0";
        }
        String column = columns.requireColumn(scope.dimension());
        List<String> targets = scope.targets().stream().sorted().toList();
        parameters.addAll(targets);
        return column + " IN (" + String.join(", ", targets.stream().map(ignored -> "?").toList()) + ")";
    }

    private static void validateIdentity(AuthorizationSubject subject, DataScopePlan plan) {
        if (subject.tenantId() != plan.tenantId()
            || subject.membershipId() != plan.membershipId()
            || subject.permissionVersion() != plan.permissionVersion()) {
            throw new IllegalArgumentException("Subject and data-scope plan identity do not match");
        }
    }
}
