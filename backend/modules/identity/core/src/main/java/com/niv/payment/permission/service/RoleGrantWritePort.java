package com.niv.payment.permission.service;

@FunctionalInterface
public interface RoleGrantWritePort {
    /**
     * Implementations must replace grants, bump affected membership permission
     * versions, and append audit/outbox events in one database transaction.
     */
    void replaceAtomically(RoleGrantChangeCommand command);
}
