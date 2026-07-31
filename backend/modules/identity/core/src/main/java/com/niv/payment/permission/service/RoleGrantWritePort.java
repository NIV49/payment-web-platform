package com.niv.payment.permission.service;

@FunctionalInterface
public interface RoleGrantWritePort {
    /**
     * Replaces grants, bumps affected permission versions, and appends audit and
     * outbox records in one transaction.
     */
    RoleGrantModels.RoleGrants replaceAtomically(RoleGrantChangeCommand command);
}
