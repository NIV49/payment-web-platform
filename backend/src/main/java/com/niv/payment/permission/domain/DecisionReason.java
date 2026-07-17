package com.niv.payment.permission.domain;

public enum DecisionReason {
    ALLOWED,
    TENANT_MISMATCH,
    PERMISSION_VERSION_STALE,
    PERMISSION_DENIED,
    SCOPE_DENIED,
    STEP_UP_REQUIRED,
    APPROVAL_CONTEXT_REQUIRED,
    SEPARATION_OF_DUTY
}
