package com.niv.payment.permission.datascope;

import java.util.List;
import java.util.Objects;

public record SqlPredicate(String sql, List<Object> parameters) {
    public SqlPredicate {
        if (Objects.requireNonNull(sql, "sql").isBlank()) {
            throw new IllegalArgumentException("SQL predicate cannot be blank");
        }
        parameters = parameters == null ? List.of() : List.copyOf(parameters);
    }
}
