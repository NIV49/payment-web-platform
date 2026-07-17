package com.niv.payment.permission.application;

import com.niv.payment.permission.datascope.SqlPredicate;
import com.niv.payment.permission.datascope.StructuredPredicateCompiler;
import com.niv.payment.permission.datascope.WhitelistedColumns;
import com.niv.payment.permission.domain.AuthorizationSubject;
import com.niv.payment.permission.domain.PermissionCode;

import java.util.Objects;

/**
 * Safe interception boundary. It returns a parameterized predicate for an
 * explicitly registered mapper instead of rewriting arbitrary SQL text.
 */
public final class PermissionDataScopeInterceptor {
    private final DefaultDataScopePlanner planner;
    private final StructuredPredicateCompiler compiler;

    public PermissionDataScopeInterceptor(DefaultDataScopePlanner planner,
                                          StructuredPredicateCompiler compiler) {
        this.planner = Objects.requireNonNull(planner, "planner");
        this.compiler = Objects.requireNonNull(compiler, "compiler");
    }

    public SqlPredicate intercept(AuthorizationSubject subject,
                                  PermissionCode permission,
                                  WhitelistedColumns columns) {
        return compiler.compile(subject, planner.plan(subject, permission), columns);
    }
}
