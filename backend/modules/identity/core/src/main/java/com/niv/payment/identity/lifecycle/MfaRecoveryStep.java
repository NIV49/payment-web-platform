package com.niv.payment.identity.lifecycle;

public enum MfaRecoveryStep {
    MFA_CREDENTIALS,
    RECOVERY_CODES,
    KEYCLOAK_SESSIONS,
    APPLICATION_SESSIONS
}
