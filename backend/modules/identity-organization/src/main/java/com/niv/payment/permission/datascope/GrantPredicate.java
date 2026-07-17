package com.niv.payment.permission.datascope;

import com.niv.payment.permission.domain.DimensionScope;

import java.util.List;

public record GrantPredicate(long grantId, List<DimensionScope> scopes) {
    public GrantPredicate {
        if (grantId <= 0) {
            throw new IllegalArgumentException("Grant identifier must be positive");
        }
        scopes = scopes == null ? List.of() : List.copyOf(scopes);
    }
}
