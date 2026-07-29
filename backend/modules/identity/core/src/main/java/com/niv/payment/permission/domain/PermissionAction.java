package com.niv.payment.permission.domain;

import java.util.Locale;

/** Controlled authorization semantics for the action segment of a permission code. */
public enum PermissionAction {
    VIEW(true),
    READ(true),
    CREATE(false),
    UPDATE(false),
    DELETE(false),
    DISABLE(false),
    ASSIGN_ROLE(false),
    MANAGE(false),
    APPROVE(false),
    UNKNOWN(false);

    private final boolean readOnly;

    PermissionAction(boolean readOnly) {
        this.readOnly = readOnly;
    }

    public boolean readOnly() {
        return readOnly;
    }

    static PermissionAction fromCode(String actionCode) {
        try {
            return valueOf(actionCode.toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException exception) {
            return UNKNOWN;
        }
    }
}
