package com.niv.payment.permission.domain;

import java.util.Objects;
import java.util.regex.Pattern;

public record PermissionCode(String value) {
    private static final Pattern FORMAT = Pattern.compile("[a-z][a-z0-9_-]*:[a-z][a-z0-9_-]*");

    public PermissionCode {
        Objects.requireNonNull(value, "value");
        if (!FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("Permission code must use lower-case resource:action segments");
        }
    }

    public static PermissionCode of(String value) {
        return new PermissionCode(value);
    }

    /**
     * Returns an exact, controlled action classification. Unknown action codes stay UNKNOWN so
     * policy that requires read-only semantics fails closed instead of guessing from a suffix.
     */
    public PermissionAction action() {
        return PermissionAction.fromCode(value.substring(value.indexOf(':') + 1));
    }

    @Override
    public String toString() {
        return value;
    }
}
