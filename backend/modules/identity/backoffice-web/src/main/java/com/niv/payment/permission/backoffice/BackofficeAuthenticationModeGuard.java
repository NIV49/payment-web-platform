package com.niv.payment.permission.backoffice;

final class BackofficeAuthenticationModeGuard {
    BackofficeAuthenticationModeGuard(boolean localProfile, boolean localLoginEnabled,
                                      boolean oidcEnabled) {
        if (localLoginEnabled != localProfile) {
            throw new IllegalStateException("Local password login is allowed only in the local profile");
        }
        if (localLoginEnabled == oidcEnabled) {
            throw new IllegalStateException("Exactly one backoffice authentication mode must be enabled");
        }
    }
}
