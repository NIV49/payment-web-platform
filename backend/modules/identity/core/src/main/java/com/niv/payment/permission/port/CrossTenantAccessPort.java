package com.niv.payment.permission.port;

import com.niv.payment.permission.domain.AuthorizationSubject;
import com.niv.payment.permission.domain.PermissionGrant;
import com.niv.payment.permission.domain.ResourceContext;

/**
 * Supplies trusted Party/Relationship evidence for read access to a resource owned by another tenant.
 * A positive result supplements an explicit IAM grant; it never creates a permission by itself.
 */
@FunctionalInterface
public interface CrossTenantAccessPort {
    boolean allows(AuthorizationSubject subject, PermissionGrant grant, ResourceContext resource);
}
