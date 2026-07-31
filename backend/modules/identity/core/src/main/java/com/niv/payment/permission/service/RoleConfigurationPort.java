package com.niv.payment.permission.service;

@FunctionalInterface
public interface RoleConfigurationPort {
    RoleConfigurationModels.RoleConfiguration replaceAtomically(RoleConfigurationCommand command);
}
