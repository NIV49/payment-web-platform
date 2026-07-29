package com.niv.payment.permission.port;

/** Signals that a permission snapshot read raced with a committed version change. */
public final class StalePermissionVersionException extends RuntimeException {
    public StalePermissionVersionException() {
        super("Permission version changed while loading the grant snapshot");
    }
}
