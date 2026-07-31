package com.niv.payment.permission.service;

import java.util.List;

public final class RoleConfigurationModels {
    private RoleConfigurationModels() {
    }

    public record RoleConfiguration(long roleId, long roleVersion, List<Long> menuIds,
                                    List<RoleGrantModels.Selection> grants, boolean editable) {
        public RoleConfiguration {
            if (roleId <= 0 || roleVersion < 0) {
                throw new IllegalArgumentException("Role configuration identity or version is invalid");
            }
            menuIds = menuIds == null ? List.of() : List.copyOf(menuIds);
            grants = grants == null ? List.of() : List.copyOf(grants);
        }
    }
}
