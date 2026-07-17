package com.niv.payment.permission.service;

import com.niv.payment.permission.domain.PermissionGrant;
import com.niv.payment.permission.domain.PermissionDefinition;
import com.niv.payment.permission.domain.RiskLevel;
import com.niv.payment.permission.port.PermissionCatalogPort;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

public final class RoleGrantAdministrationService {
    private final RoleGrantWritePort writePort;
    private final PermissionCatalogPort permissionCatalog;

    public RoleGrantAdministrationService(RoleGrantWritePort writePort,
                                          PermissionCatalogPort permissionCatalog) {
        this.writePort = Objects.requireNonNull(writePort, "writePort");
        this.permissionCatalog = Objects.requireNonNull(permissionCatalog, "permissionCatalog");
    }

    public void replace(RoleGrantChangeCommand command) {
        Objects.requireNonNull(command, "command");
        Set<Long> grantIds = new HashSet<>();
        for (PermissionGrant grant : command.grants()) {
            if (grant.roleId() != command.roleId()) {
                throw new IllegalArgumentException("Every grant must belong to the role being changed");
            }
            if (!grant.active()) {
                throw new IllegalArgumentException("Replacement payload cannot contain inactive grants");
            }
            if (!grantIds.add(grant.id())) {
                throw new IllegalArgumentException("Duplicate grant identifier in replacement payload");
            }
            PermissionDefinition definition = permissionCatalog.require(grant.permission());
            if (!definition.active()) {
                throw new IllegalArgumentException("Permission is disabled: " + grant.permission());
            }
            if (grant.riskLevel() != definition.riskLevel()
                || !grant.requiredDimensions().equals(definition.requiredDimensions())
                || grant.requiresStepUp() != definition.requiresStepUp()
                || grant.requiresApproval() != definition.requiresApproval()) {
                throw new IllegalArgumentException("Grant metadata does not match the trusted permission catalog");
            }
            if (grant.riskLevel() == RiskLevel.FUND && !grant.requiresStepUp()) {
                throw new IllegalArgumentException("FUND permissions must explicitly require step-up authentication");
            }
        }
        writePort.replaceAtomically(command);
    }
}
