package com.niv.payment.permission.datascope;

import com.niv.payment.permission.domain.ScopeDimension;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

public final class WhitelistedColumns {
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)?");

    private final String tenantColumn;
    private final Map<ScopeDimension, String> scopeColumns;

    public WhitelistedColumns(String tenantColumn, Map<ScopeDimension, String> scopeColumns) {
        this.tenantColumn = validateIdentifier(tenantColumn);
        Objects.requireNonNull(scopeColumns, "scopeColumns");
        EnumMap<ScopeDimension, String> validated = new EnumMap<>(ScopeDimension.class);
        scopeColumns.forEach((dimension, column) ->
            validated.put(Objects.requireNonNull(dimension, "dimension"), validateIdentifier(column)));
        this.scopeColumns = Map.copyOf(validated);
    }

    public String tenantColumn() {
        return tenantColumn;
    }

    public String requireColumn(ScopeDimension dimension) {
        String column = scopeColumns.get(dimension);
        if (column == null) {
            throw new IllegalArgumentException("No whitelisted SQL column for " + dimension);
        }
        return column;
    }

    private static String validateIdentifier(String identifier) {
        Objects.requireNonNull(identifier, "identifier");
        if (!IDENTIFIER.matcher(identifier).matches()) {
            throw new IllegalArgumentException("Unsafe SQL identifier: " + identifier);
        }
        return identifier;
    }
}
