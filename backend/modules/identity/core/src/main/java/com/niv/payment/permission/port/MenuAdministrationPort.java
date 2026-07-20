package com.niv.payment.permission.port;

import com.niv.payment.permission.service.IdentityModels;

import java.util.List;

public interface MenuAdministrationPort {
    List<IdentityModels.Menu> findMenus(long tenantId);
    long createMenu(long tenantId, long operatorMembershipId, IdentityModels.MenuCommand command);
    void updateMenu(long tenantId, long operatorMembershipId, long menuId, IdentityModels.MenuCommand command);
    void deleteMenu(long tenantId, long operatorMembershipId, long menuId);
    boolean menuNameExists(long tenantId, String name, Long excludedId);
    boolean menuPathExists(long tenantId, String path, Long excludedId);
}
