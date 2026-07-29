package com.niv.payment.permission.port;

import com.niv.payment.permission.domain.AdministrationActor;
import com.niv.payment.permission.service.IdentityModels;

import java.util.List;

public interface MenuAdministrationPort {
    List<IdentityModels.Menu> findMenus(long tenantId);
    long createMenu(long tenantId, AdministrationActor actor, IdentityModels.MenuCommand command);
    void updateMenu(long tenantId, AdministrationActor actor, long menuId,
                    IdentityModels.MenuCommand command, long expectedVersion);
    void deleteMenu(long tenantId, AdministrationActor actor, long menuId, long expectedVersion);
    boolean menuNameExists(long tenantId, String name, Long excludedId);
    boolean menuPathExists(long tenantId, String path, Long excludedId);
}
