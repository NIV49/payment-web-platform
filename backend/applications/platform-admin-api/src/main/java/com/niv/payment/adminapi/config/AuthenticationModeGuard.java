package com.niv.payment.adminapi.config;

final class AuthenticationModeGuard {
    AuthenticationModeGuard(boolean localProfile, boolean localLoginEnabled, boolean oidcEnabled) {
        if (localLoginEnabled != localProfile) {
            throw new IllegalStateException("Local password login is allowed only in the local profile");
        }
        if (localLoginEnabled == oidcEnabled) {
            throw new IllegalStateException("Exactly one platform authentication mode must be enabled");
        }
    }
}
