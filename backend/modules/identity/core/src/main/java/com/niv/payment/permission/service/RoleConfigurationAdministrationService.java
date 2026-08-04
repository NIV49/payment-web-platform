package com.niv.payment.permission.service;

import com.niv.payment.permission.domain.ScopeDimension;
import com.niv.payment.permission.domain.ScopeMode;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/** Validates the complete role configuration before crossing one atomic persistence boundary. */
public final class RoleConfigurationAdministrationService {
    private final RoleConfigurationPort port;
    private final boolean legacyAdministrationCutoverComplete;

    public RoleConfigurationAdministrationService(
        RoleConfigurationPort port, boolean legacyAdministrationCutoverComplete) {
        this.port = Objects.requireNonNull(port, "port");
        this.legacyAdministrationCutoverComplete = legacyAdministrationCutoverComplete;
    }

    public RoleConfigurationModels.RoleConfiguration replace(RoleConfigurationCommand command) {
        Objects.requireNonNull(command, "command");
        validate(command.menuIds(), command.grants());
        return port.replaceAtomically(command);
    }

    public RoleConfigurationModels.RoleConfiguration create(RoleConfigurationCreateCommand command) {
        Objects.requireNonNull(command, "command");
        validate(command.menuIds(), command.grants());
        return port.createAtomically(command);
    }

    private void validate(
        java.util.List<Long> menuIds,
        java.util.List<RoleGrantModels.Selection> grants) {
        if (!legacyAdministrationCutoverComplete) {
            throw new LegacyAdministrationCutoverRequiredException();
        }
        if (new HashSet<>(menuIds).size() != menuIds.size()) {
            throw new IllegalArgumentException("Duplicate role menu identifier");
        }
        Set<String> keys = new HashSet<>();
        Set<String> permissions = new HashSet<>();
        for (RoleGrantModels.Selection grant : grants) {
            if (RoleGrantAdministrationService.PROTECTED_PORTAL_GRANT_KEY.equals(grant.grantKey())) {
                throw new IllegalArgumentException("Grant key is reserved for server-managed access");
            }
            if (!RoleGrantAdministrationService.GRANTABLE_CODES.contains(grant.permission().value())) {
                throw new IllegalArgumentException("Permission is not grantable from this administration surface");
            }
            if (grant.dimension() != ScopeDimension.TENANT || grant.mode() != ScopeMode.TENANT_ALL) {
                throw new IllegalArgumentException("Only TENANT/TENANT_ALL grants are supported");
            }
            if (!keys.add(grant.grantKey()) || !permissions.add(grant.permission().value())) {
                throw new IllegalArgumentException("Duplicate grant key or permission");
            }
        }
    }

    public static final class LegacyAdministrationCutoverRequiredException extends IllegalStateException {
        public LegacyAdministrationCutoverRequiredException() {
            super("Role configuration replacement is disabled until the legacy administration cutover is complete");
        }
    }
}
