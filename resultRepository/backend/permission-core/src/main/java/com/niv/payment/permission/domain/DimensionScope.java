package com.niv.payment.permission.domain;

import java.util.Objects;
import java.util.Set;

public record DimensionScope(ScopeDimension dimension, ScopeMode mode, Set<String> targets) {
    public DimensionScope {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(mode, "mode");
        targets = targets == null ? Set.of() : Set.copyOf(targets);
        if ((mode == ScopeMode.SPECIFIED || mode == ScopeMode.ASSIGNED) && targets.isEmpty()) {
            throw new IllegalArgumentException(mode + " scope requires at least one target");
        }
    }
}
