package com.niv.payment.permission.port;

import com.niv.payment.permission.service.IdentityModels;

import java.util.List;
import java.util.Optional;

public interface IdentityQueryPort {
    Optional<IdentityModels.CurrentUser> findCurrentUser(long tenantId, long membershipId);
    List<String> findPermissionCodes(long tenantId, long membershipId);
    List<IdentityModels.Menu> findAccessibleMenus(long tenantId, long membershipId);
}
