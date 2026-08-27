package com.niv.payment.permission;

import com.niv.payment.permission.domain.AccountDomain;
import com.niv.payment.permission.domain.AdministrationActor;
import com.niv.payment.permission.port.PlatformAdministrationDirectoryPort;
import com.niv.payment.permission.service.IdentityAdministrationService;
import com.niv.payment.permission.service.IdentityModels;
import com.niv.payment.permission.service.PlatformAdministrationDirectoryService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PlatformAdministrationDirectoryServiceTest {
    private final RecordingPort port = new RecordingPort();
    private final PlatformAdministrationDirectoryService service =
        new PlatformAdministrationDirectoryService(port);
    private final AdministrationActor actor = new AdministrationActor(1000, 100, 3, 0);

    @Test
    void fixesPlatformQueriesToTheSourceTenantAndValidatesRolePaging() {
        var query = new IdentityModels.RoleQuery(
            "operator", null, 1, null, null, null, 1, 200);

        service.findRoles(1, actor,
            new PlatformAdministrationDirectoryPort.RoleDirectoryQuery(
                AccountDomain.PLATFORM, null, query));

        assertEquals(1L, port.roleQuery.tenantId());
        assertEquals(AccountDomain.PLATFORM, port.roleQuery.accountDomain());
        assertThrows(IdentityAdministrationService.InvalidCommandException.class,
            () -> service.findRoles(1, actor,
                new PlatformAdministrationDirectoryPort.RoleDirectoryQuery(
                    AccountDomain.PLATFORM, 2L, query)));
        assertThrows(IdentityAdministrationService.InvalidCommandException.class,
            () -> service.findRoles(1, actor,
                new PlatformAdministrationDirectoryPort.RoleDirectoryQuery(
                    AccountDomain.PLATFORM, null,
                    new IdentityModels.RoleQuery(
                        null, null, null, null, null, null, 1, 201))));
    }

    @Test
    void requiresAnExactPositiveTenantForMerchantAndAgentQueries() {
        var roleQuery = new IdentityModels.RoleQuery(
            null, null, null, null, null, null, 1, 20);

        assertThrows(IdentityAdministrationService.InvalidCommandException.class,
            () -> service.findRoles(1, actor,
                new PlatformAdministrationDirectoryPort.RoleDirectoryQuery(
                    AccountDomain.MERCHANT, null, roleQuery)));
        assertThrows(IdentityAdministrationService.InvalidCommandException.class,
            () -> service.findMenus(1, actor,
                new PlatformAdministrationDirectoryPort.MenuDirectoryQuery(
                    AccountDomain.AGENT, 0L, false)));

        service.findMenus(1, actor,
            new PlatformAdministrationDirectoryPort.MenuDirectoryQuery(
                AccountDomain.AGENT, 3L, true));
        assertEquals(3L, port.menuQuery.tenantId());
        assertEquals(AccountDomain.AGENT, port.menuQuery.accountDomain());
    }

    @Test
    void rejectsInvalidRoleFiltersBeforeCallingPersistence() {
        var start = Instant.parse("2026-08-11T01:00:00Z");
        var end = Instant.parse("2026-08-11T00:00:00Z");

        assertThrows(IdentityAdministrationService.InvalidCommandException.class,
            () -> service.findRoles(1, actor,
                new PlatformAdministrationDirectoryPort.RoleDirectoryQuery(
                    AccountDomain.MERCHANT, 2L,
                    new IdentityModels.RoleQuery(
                        null, null, 2, null, null, null, 1, 20))));
        assertThrows(IdentityAdministrationService.InvalidCommandException.class,
            () -> service.findRoles(1, actor,
                new PlatformAdministrationDirectoryPort.RoleDirectoryQuery(
                    AccountDomain.MERCHANT, 2L,
                    new IdentityModels.RoleQuery(
                        null, null, null, null, start, end, 1, 20))));
    }

    @Test
    void failsClosedWhenStoredMenuRowsContainACycle() {
        port.menus = List.of(
            menu(10, 11),
            menu(11, 10));

        assertThrows(IdentityAdministrationService.TreeLimitExceededException.class,
            () -> service.findMenus(1, actor,
                new PlatformAdministrationDirectoryPort.MenuDirectoryQuery(
                    AccountDomain.PLATFORM, null, false)));
    }

    private static PlatformAdministrationDirectoryPort.DirectoryMenu menu(
        long id, long parentId) {
        return new PlatformAdministrationDirectoryPort.DirectoryMenu(
            new PlatformAdministrationDirectoryPort.DirectoryScope(
                AccountDomain.PLATFORM, 1, "Platform",
                PlatformAdministrationDirectoryPort.ManagementMode.SAME_TENANT),
            new IdentityModels.Menu(
                id, parentId, "menu", "menu-" + id, "/" + id,
                "/test", null, null, "{}", 1, 0, false));
    }

    private static final class RecordingPort implements PlatformAdministrationDirectoryPort {
        private RoleDirectoryQuery roleQuery;
        private MenuDirectoryQuery menuQuery;
        private List<DirectoryMenu> menus = List.of();

        @Override
        public IdentityModels.Page<DirectoryRole> findRoles(
            long sourceTenantId, AdministrationActor actor, RoleDirectoryQuery query) {
            roleQuery = query;
            return new IdentityModels.Page<>(List.of(), 0);
        }

        @Override
        public List<DirectoryMenu> findMenus(
            long sourceTenantId, AdministrationActor actor, MenuDirectoryQuery query) {
            menuQuery = query;
            return menus;
        }
    }
}
