package com.niv.payment.permission.service;

@FunctionalInterface
public interface RoleConfigurationPort {
    RoleConfigurationModels.RoleConfiguration replaceAtomically(RoleConfigurationCommand command);

    default RoleConfigurationModels.RoleConfiguration createAtomically(
        RoleConfigurationCreateCommand command) {
        throw new UnsupportedOperationException("Atomic role configuration creation is unavailable");
    }
}
