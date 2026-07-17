package com.niv.payment.permission.domain;

import java.util.Objects;

public record AuthorizationRequest(
    AuthorizationSubject subject,
    PermissionCode permission,
    ResourceContext resource,
    Long initiatorMembershipId
) {
    public AuthorizationRequest {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(permission, "permission");
        Objects.requireNonNull(resource, "resource");
    }
}
