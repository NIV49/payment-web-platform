package com.niv.payment.permission.domain;

import java.util.Objects;
import java.util.Set;

public record DimensionScope(ScopeDimension dimension, ScopeMode mode, Set<String> targets) {
    public DimensionScope {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(mode, "mode");
        targets = targets == null ? Set.of() : Set.copyOf(targets);
        if (!supports(dimension, mode)) {
            throw new IllegalArgumentException(
                "Scope mode " + mode + " is not valid for dimension " + dimension);
        }
        if ((mode == ScopeMode.SPECIFIED || mode == ScopeMode.ASSIGNED) && targets.isEmpty()) {
            throw new IllegalArgumentException(mode + " scope requires at least one target");
        }
    }

    private static boolean supports(ScopeDimension dimension, ScopeMode mode) {
        return switch (dimension) {
            case TENANT -> mode == ScopeMode.TENANT_ALL;
            case OWNER -> mode == ScopeMode.SELF;
            case DEPARTMENT -> mode == ScopeMode.SELF || mode == ScopeMode.DEPARTMENT
                || mode == ScopeMode.DEPARTMENT_AND_CHILDREN || mode == ScopeMode.SPECIFIED;
            case CUSTOMER -> mode == ScopeMode.ASSIGNED || mode == ScopeMode.SPECIFIED;
            case MERCHANT -> mode == ScopeMode.ASSIGNED || mode == ScopeMode.SPECIFIED
                || mode == ScopeMode.RELATION_CURRENT
                || mode == ScopeMode.RELATION_AT_EVENT;
            case MARKET, CHANNEL -> mode == ScopeMode.SPECIFIED;
        };
    }
}
