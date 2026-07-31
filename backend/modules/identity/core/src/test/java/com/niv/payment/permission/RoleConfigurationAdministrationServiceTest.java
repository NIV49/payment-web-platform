package com.niv.payment.permission;

import com.niv.payment.permission.domain.AdministrationActor;
import com.niv.payment.permission.domain.PermissionCode;
import com.niv.payment.permission.domain.ScopeDimension;
import com.niv.payment.permission.domain.ScopeMode;
import com.niv.payment.permission.service.RoleConfigurationAdministrationService;
import com.niv.payment.permission.service.RoleConfigurationCommand;
import com.niv.payment.permission.service.RoleConfigurationModels;
import com.niv.payment.permission.service.RoleGrantModels;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RoleConfigurationAdministrationServiceTest {
    private static final AdministrationActor ACTOR = new AdministrationActor(5L, 6L, 7L, 8L);

    @Test
    void delegatesTheCompleteConfigurationToOneAtomicWriteBoundary() {
        AtomicInteger writes = new AtomicInteger();
        var service = new RoleConfigurationAdministrationService(command -> {
            writes.incrementAndGet();
            return new RoleConfigurationModels.RoleConfiguration(
                command.roleId(), command.expectedRoleVersion() + 1, command.menuIds(), command.grants(), true);
        }, true);

        var result = service.replace(command(List.of(selection("user-view", "user:view"))));

        assertEquals(1, writes.get());
        assertEquals(10L, result.roleVersion());
        assertEquals(List.of(101L, 102L), result.menuIds());
    }

    @Test
    void rejectsUnsafeGrantIntentBeforePersistence() {
        var service = new RoleConfigurationAdministrationService(command -> {
            throw new AssertionError("Invalid configuration must not reach persistence");
        }, true);

        assertThrows(IllegalArgumentException.class, () -> service.replace(command(List.of(
            selection("grant-admin", "role:grant-update")))));
        assertThrows(IllegalArgumentException.class, () -> service.replace(command(List.of(
            selection("unknown", "payout:view")))));
        assertThrows(IllegalArgumentException.class, () -> service.replace(command(List.of(
            new RoleGrantModels.Selection("wrong-scope", PermissionCode.of("user:view"),
                ScopeDimension.DEPARTMENT, ScopeMode.DEPARTMENT)))));
    }

    @Test
    void rejectsDuplicateMenusPermissionsAndGrantKeys() {
        var service = new RoleConfigurationAdministrationService(command -> {
            throw new AssertionError("Invalid configuration must not reach persistence");
        }, true);

        assertThrows(IllegalArgumentException.class, () -> service.replace(new RoleConfigurationCommand(
            3L, 4L, 9L, ACTOR, "Support", 1, null, List.of(101L, 101L), "test", List.of())));
        assertThrows(IllegalArgumentException.class, () -> service.replace(command(List.of(
            selection("first", "user:view"), selection("second", "user:view")))));
        assertThrows(IllegalArgumentException.class, () -> service.replace(command(List.of(
            selection("same", "user:view"), selection("same", "role:view")))));
    }

    @Test
    void remainsClosedBeforeLegacyCutover() {
        var service = new RoleConfigurationAdministrationService(command -> {
            throw new AssertionError("Write must remain disabled before cutover");
        }, false);

        assertThrows(RoleConfigurationAdministrationService.LegacyAdministrationCutoverRequiredException.class,
            () -> service.replace(command(List.of())));
    }

    private static RoleConfigurationCommand command(List<RoleGrantModels.Selection> grants) {
        return new RoleConfigurationCommand(
            3L, 4L, 9L, ACTOR, "Support", 1, "role remark", List.of(101L, 102L), "test", grants);
    }

    private static RoleGrantModels.Selection selection(String key, String code) {
        return new RoleGrantModels.Selection(
            key, PermissionCode.of(code), ScopeDimension.TENANT, ScopeMode.TENANT_ALL);
    }
}
