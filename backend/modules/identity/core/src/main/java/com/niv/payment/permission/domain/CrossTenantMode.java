package com.niv.payment.permission.domain;

/**
 * Declares whether a permission may be evaluated against a resource owned by another tenant.
 */
public enum CrossTenantMode {
    SAME_TENANT_ONLY,
    RELATED_PARTY_READ
}
