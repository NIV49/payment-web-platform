package com.niv.payment.permission;

import com.niv.payment.permission.domain.AdministrationActor;
import com.niv.payment.permission.domain.PermissionCode;
import com.niv.payment.permission.domain.ScopeDimension;
import com.niv.payment.permission.domain.ScopeMode;
import com.niv.payment.permission.service.RoleGrantAdministrationService;
import com.niv.payment.permission.service.RoleGrantChangeCommand;
import com.niv.payment.permission.service.RoleGrantModels;
import com.niv.payment.permission.service.RoleGrantReadPort;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoleGrantAdministrationServiceTest {
    private static final AdministrationActor ACTOR = new AdministrationActor(5L, 6L, 0L, 0L);

    @Test
    void exposesOnlyTheExactNormalTenantAdministrationCatalog() {
        var catalog = RoleGrantAdministrationService.GRANTABLE_CODES.stream().sorted()
            .map(code -> {
                PermissionCode permission = PermissionCode.of(code);
                String[] segments = code.split(":", 2);
                return new RoleGrantModels.GrantablePermission(permission, segments[0], segments[1]);
            }).toList();
        var service = new RoleGrantAdministrationService(readPort(catalog), command -> {
            throw new AssertionError("Write not expected");
        });

        assertEquals(18, service.grantablePermissions(3L, ACTOR).size());
    }

    @Test
    void failsClosedWhenTheDatabaseCatalogIsIncomplete() {
        var service = new RoleGrantAdministrationService(readPort(List.of()), command -> {
            throw new AssertionError("Write not expected");
        });

        assertThrows(IllegalStateException.class, () -> service.grantablePermissions(3L, ACTOR));
    }

    @Test
    void disablesGrantEditingAndRejectsReplacementBeforeLegacyCutover() {
        AtomicBoolean called = new AtomicBoolean();
        var service = new RoleGrantAdministrationService(readPort(List.of()), command -> {
            called.set(true);
            throw new AssertionError("Write must remain disabled before legacy cutover");
        });

        assertFalse(service.find(3L, ACTOR, 2L).editable());
        assertThrows(RoleGrantAdministrationService.LegacyAdministrationCutoverRequiredException.class,
            () -> service.replace(command(
                selection("user-view", "user:view"))));
        assertFalse(called.get());
    }

    @Test
    void delegatesAValidatedTenantWideReplacementToOneAtomicWriteBoundary() {
        AtomicBoolean called = new AtomicBoolean();
        RoleGrantModels.Selection selection = selection("user-view", "user:view");
        var service = new RoleGrantAdministrationService(readPort(List.of()), command -> {
            called.set(true);
            return new RoleGrantModels.RoleGrants(command.roleId(), command.expectedRoleVersion() + 1,
                true, command.grants());
        }, true);

        RoleGrantModels.RoleGrants result = service.replace(new RoleGrantChangeCommand(
            3L, 2L, 4L, ACTOR, "least privilege", List.of(selection)));

        assertTrue(called.get());
        assertEquals(5L, result.roleVersion());
    }

    @Test
    void rejectsAdminOnlyUnknownAndNonTenantGrantIntentBeforePersistence() {
        var service = new RoleGrantAdministrationService(readPort(List.of()), command -> {
            throw new AssertionError("Invalid change must not reach persistence");
        }, true);
        assertThrows(IllegalArgumentException.class, () -> service.replace(command(
            selection("grant-admin", RoleGrantAdministrationService.GRANT_UPDATE_PERMISSION))));
        assertThrows(IllegalArgumentException.class, () -> service.replace(command(
            selection("unknown", "payout:view"))));
        assertThrows(IllegalArgumentException.class, () -> service.replace(command(
            new RoleGrantModels.Selection("wrong-scope", PermissionCode.of("user:view"),
                ScopeDimension.DEPARTMENT, ScopeMode.DEPARTMENT))));
    }

    @Test
    void rejectsDuplicatePermissionOrGrantKey() {
        var service = new RoleGrantAdministrationService(readPort(List.of()), command -> {
            throw new AssertionError("Invalid change must not reach persistence");
        }, true);
        assertThrows(IllegalArgumentException.class, () -> service.replace(new RoleGrantChangeCommand(
            3L, 2L, 4L, ACTOR, "duplicate", List.of(
                selection("first", "user:view"), selection("second", "user:view")))));
        assertThrows(IllegalArgumentException.class, () -> service.replace(new RoleGrantChangeCommand(
            3L, 2L, 4L, ACTOR, "duplicate", List.of(
                selection("same", "user:view"), selection("same", "role:view")))));
    }

    private static RoleGrantChangeCommand command(RoleGrantModels.Selection selection) {
        return new RoleGrantChangeCommand(3L, 2L, 4L, ACTOR, "test", List.of(selection));
    }

    private static RoleGrantModels.Selection selection(String key, String code) {
        return new RoleGrantModels.Selection(key, PermissionCode.of(code),
            ScopeDimension.TENANT, ScopeMode.TENANT_ALL);
    }

    private static RoleGrantReadPort readPort(List<RoleGrantModels.GrantablePermission> catalog) {
        return new RoleGrantReadPort() {
            @Override
            public List<RoleGrantModels.GrantablePermission> findGrantablePermissions(
                long tenantId, AdministrationActor actor) {
                return catalog;
            }

            @Override
            public RoleGrantModels.RoleGrants findRoleGrants(
                long tenantId, AdministrationActor actor, long roleId) {
                return new RoleGrantModels.RoleGrants(roleId, 0L, true, List.of());
            }
        };
    }
}
