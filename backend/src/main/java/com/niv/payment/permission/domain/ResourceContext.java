package com.niv.payment.permission.domain;

public record ResourceContext(
    long tenantId,
    Long ownerMembershipId,
    Long departmentId,
    String customerRef,
    String merchantRef,
    String marketRef,
    String channelRef
) {
    public ResourceContext {
        if (tenantId <= 0) {
            throw new IllegalArgumentException("Resource tenant identifier must be positive");
        }
    }

    public String valueOf(ScopeDimension dimension) {
        return switch (dimension) {
            case TENANT -> Long.toString(tenantId);
            case OWNER -> ownerMembershipId == null ? null : ownerMembershipId.toString();
            case DEPARTMENT -> departmentId == null ? null : departmentId.toString();
            case CUSTOMER -> customerRef;
            case MERCHANT -> merchantRef;
            case MARKET -> marketRef;
            case CHANNEL -> channelRef;
        };
    }
}
