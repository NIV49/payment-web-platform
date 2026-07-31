package com.niv.payment.permission.service;

import com.niv.payment.permission.domain.AdministrationActor;
import com.niv.payment.permission.domain.ScopeDimension;
import com.niv.payment.permission.domain.ScopeMode;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Constrained administration surface for normal, tenant-wide IAM grants. */
public final class RoleGrantAdministrationService {
    public static final String GRANT_UPDATE_PERMISSION = "role:grant-update";
    public static final Set<String> GRANTABLE_CODES = Set.of(
        "user:view", "user:create", "user:update", "user:delete", "user:disable", "user:assign-role",
        "role:view", "role:create", "role:update", "role:delete",
        "menu:view", "menu:create", "menu:update", "menu:delete",
        "department:view", "department:create", "department:update", "department:delete");

    private final RoleGrantReadPort readPort;
    private final RoleGrantWritePort writePort;
    private final boolean legacyAdministrationCutoverComplete;

    public RoleGrantAdministrationService(RoleGrantReadPort readPort, RoleGrantWritePort writePort) {
        this(readPort, writePort, false);
    }

    public RoleGrantAdministrationService(RoleGrantReadPort readPort, RoleGrantWritePort writePort,
                                          boolean legacyAdministrationCutoverComplete) {
        this.readPort = Objects.requireNonNull(readPort, "readPort");
        this.writePort = Objects.requireNonNull(writePort, "writePort");
        this.legacyAdministrationCutoverComplete = legacyAdministrationCutoverComplete;
    }

    public List<RoleGrantModels.GrantablePermission> grantablePermissions(
        long tenantId, AdministrationActor actor) {
        if (tenantId <= 0) {
            throw new IllegalArgumentException("Tenant identifier must be positive");
        }
        List<RoleGrantModels.GrantablePermission> catalog =
            readPort.findGrantablePermissions(tenantId, Objects.requireNonNull(actor, "actor"));
        Set<String> actual = catalog.stream().map(item -> item.code().value())
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!actual.equals(GRANTABLE_CODES)) {
            throw new IllegalStateException("Grantable permission catalog is incomplete or unsafe");
        }
        return List.copyOf(catalog);
    }

    public RoleGrantModels.RoleGrants find(long tenantId, AdministrationActor actor, long roleId) {
        if (tenantId <= 0 || roleId <= 0) {
            throw new IllegalArgumentException("Tenant and role identifiers must be positive");
        }
        RoleGrantModels.RoleGrants current =
            readPort.findRoleGrants(tenantId, Objects.requireNonNull(actor, "actor"), roleId);
        if (legacyAdministrationCutoverComplete || !current.editable()) {
            return current;
        }
        return new RoleGrantModels.RoleGrants(
            current.roleId(), current.roleVersion(), false, current.grants());
    }

    public RoleGrantModels.RoleGrants replace(RoleGrantChangeCommand command) {
        Objects.requireNonNull(command, "command");
        if (!legacyAdministrationCutoverComplete) {
            throw new LegacyAdministrationCutoverRequiredException();
        }
        Set<String> keys = new HashSet<>();
        Set<String> permissions = new HashSet<>();
        for (RoleGrantModels.Selection grant : command.grants()) {
            if (!GRANTABLE_CODES.contains(grant.permission().value())) {
                throw new IllegalArgumentException("Permission is not grantable from this administration surface");
            }
            if (grant.dimension() != ScopeDimension.TENANT || grant.mode() != ScopeMode.TENANT_ALL) {
                throw new IllegalArgumentException("Only TENANT/TENANT_ALL grants are supported");
            }
            if (!keys.add(grant.grantKey()) || !permissions.add(grant.permission().value())) {
                throw new IllegalArgumentException("Duplicate grant key or permission");
            }
        }
        return writePort.replaceAtomically(command);
    }

    public static final class LegacyAdministrationCutoverRequiredException extends IllegalStateException {
        public LegacyAdministrationCutoverRequiredException() {
            super("Role grant replacement is disabled until the legacy administration cutover is complete");
        }
    }
}
