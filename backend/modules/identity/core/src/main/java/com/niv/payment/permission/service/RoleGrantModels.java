package com.niv.payment.permission.service;

import com.niv.payment.permission.domain.PermissionCode;
import com.niv.payment.permission.domain.ScopeDimension;
import com.niv.payment.permission.domain.ScopeMode;

import java.util.List;
import java.util.Objects;

public final class RoleGrantModels {
    private RoleGrantModels() {
    }

    public record GrantablePermission(PermissionCode code, String resource, String action) {
        public GrantablePermission {
            Objects.requireNonNull(code, "code");
            if (resource == null || resource.isBlank() || action == null || action.isBlank()) {
                throw new IllegalArgumentException("Permission resource and action are required");
            }
        }
    }

    public record Selection(String grantKey, PermissionCode permission,
                            ScopeDimension dimension, ScopeMode mode) {
        public Selection {
            grantKey = grantKey == null ? null : grantKey.trim();
            if (grantKey == null || !grantKey.matches("[a-z][a-z0-9_-]{0,63}")) {
                throw new IllegalArgumentException("Grant key has an invalid format");
            }
            Objects.requireNonNull(permission, "permission");
            Objects.requireNonNull(dimension, "dimension");
            Objects.requireNonNull(mode, "mode");
        }
    }

    public record RoleGrants(long roleId, long roleVersion, boolean editable,
                             List<Selection> grants) {
        public RoleGrants {
            if (roleId <= 0 || roleVersion < 0) {
                throw new IllegalArgumentException("Role grant view identity or version is invalid");
            }
            grants = grants == null ? List.of() : List.copyOf(grants);
        }
    }
}
