package com.niv.payment.permission.port;

import com.niv.payment.permission.domain.PermissionCode;
import com.niv.payment.permission.domain.PermissionDefinition;

@FunctionalInterface
public interface PermissionCatalogPort {
    PermissionDefinition require(PermissionCode code);
}
