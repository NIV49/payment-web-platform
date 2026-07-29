package com.niv.payment.permission.domain;

public enum ScopeMode {
    TENANT_ALL,
    SELF,
    DEPARTMENT,
    DEPARTMENT_AND_CHILDREN,
    ASSIGNED,
    SPECIFIED,
    RELATION_CURRENT,
    RELATION_AT_EVENT
}
