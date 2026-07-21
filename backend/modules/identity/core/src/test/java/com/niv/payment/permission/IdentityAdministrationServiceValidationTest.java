package com.niv.payment.permission;

import com.niv.payment.permission.domain.AdministrationActor;
import com.niv.payment.permission.port.DepartmentAdministrationPort;
import com.niv.payment.permission.port.IdentityQueryPort;
import com.niv.payment.permission.port.MenuAdministrationPort;
import com.niv.payment.permission.port.RoleAdministrationPort;
import com.niv.payment.permission.port.UserAdministrationPort;
import com.niv.payment.permission.service.IdentityAdministrationService;
import com.niv.payment.permission.service.IdentityModels;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.List;
import java.time.Instant;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertThrows;

class IdentityAdministrationServiceValidationTest {
    private final IdentityAdministrationService service = new IdentityAdministrationService(
        unsupported(IdentityQueryPort.class),
        unsupported(UserAdministrationPort.class),
        unsupported(RoleAdministrationPort.class),
        unsupported(DepartmentAdministrationPort.class),
        unsupported(MenuAdministrationPort.class));
    private final AdministrationActor actor = new AdministrationActor(1L, 2L, 0L, 0L);

    @Test
    void rejectsPaginationWhoseDatabaseOffsetWouldOverflow() {
        var query = new IdentityModels.UserQuery(null, null, null, null, null,
            null, null, Integer.MAX_VALUE, 200);

        assertThrows(IdentityAdministrationService.InvalidCommandException.class,
            () -> service.users(1L, query));
    }

    @Test
    void rejectsUnboundedRoleAndMenuAssignmentsAtTheCoreBoundary() {
        List<Long> tooManyRoles = Collections.nCopies(257, 1L);
        List<Long> tooManyMenus = Collections.nCopies(2_049, 1L);

        assertThrows(IdentityAdministrationService.InvalidCommandException.class,
            () -> service.createUser(1L, actor,
                new IdentityModels.UserCreateCommand("bounded", "Bounded", 1L, tooManyRoles, 1, null)));
        assertThrows(IdentityAdministrationService.InvalidCommandException.class,
            () -> service.updateUser(1L, actor, 2L,
                new IdentityModels.MembershipUpdateCommand(1L, tooManyRoles, 1, 0L)));
        assertThrows(IdentityAdministrationService.InvalidCommandException.class,
            () -> service.createRole(1L, actor,
                new IdentityModels.RoleCommand("Bounded", tooManyMenus, 1, null)));
    }

    @Test
    void rejectsDepartmentTreesAboveThePerTenantNodeLimit() {
        List<IdentityModels.Department> rows = IntStream.rangeClosed(1, 2_001)
            .mapToObj(id -> new IdentityModels.Department(
                id, null, "Department " + id, 1, null, 0L, Instant.EPOCH))
            .toList();
        IdentityAdministrationService bounded = serviceWith(
            unsupported(IdentityQueryPort.class), returning(DepartmentAdministrationPort.class,
                "findDepartments", rows), unsupported(MenuAdministrationPort.class));

        assertThrows(IdentityAdministrationService.TreeLimitExceededException.class,
            () -> bounded.departments(1L));
    }

    @Test
    void rejectsMenuTreesDeeperThanThirtyTwoLevels() {
        List<IdentityModels.Menu> rows = IntStream.rangeClosed(1, 33)
            .mapToObj(id -> new IdentityModels.Menu(id, id == 1 ? null : (long) id - 1,
                "menu", "Menu" + id, "/menu-" + id, "/system/user/list", null, null,
                "{\"title\":\"system.user.title\"}", 1, 0L))
            .toList();
        IdentityAdministrationService bounded = serviceWith(
            unsupported(IdentityQueryPort.class), unsupported(DepartmentAdministrationPort.class),
            returning(MenuAdministrationPort.class, "findMenus", rows));

        assertThrows(IdentityAdministrationService.TreeLimitExceededException.class,
            () -> bounded.menus(1L));
    }

    @Test
    void accessibleMenuCyclesFailClosedBeforeWebTreeAssembly() {
        List<IdentityModels.Menu> rows = List.of(
            menu(1, 2L), menu(2, 1L));
        IdentityAdministrationService bounded = serviceWith(
            returning(IdentityQueryPort.class, "findAccessibleMenus", rows),
            unsupported(DepartmentAdministrationPort.class), unsupported(MenuAdministrationPort.class));

        assertThrows(IdentityAdministrationService.TreeLimitExceededException.class,
            () -> bounded.accessibleMenus(1L, 10L));
    }

    @Test
    void rejectsNegativeExpectedVersionsAtTheCoreBoundary() {
        var role = new IdentityModels.RoleCommand("Role", List.of(), 1, null);
        var department = new IdentityModels.DepartmentCommand(null, "Department", 1, null);
        var menu = new IdentityModels.MenuCommand(
            null, "menu", "Menu", "/menu", "/system/user/list", null, null, "{}", 1);

        assertThrows(IdentityAdministrationService.InvalidCommandException.class,
            () -> service.deleteUser(1L, actor, 2L, -1L));
        assertThrows(IdentityAdministrationService.InvalidCommandException.class,
            () -> service.updateRole(1L, actor, 2L, role, -1L));
        assertThrows(IdentityAdministrationService.InvalidCommandException.class,
            () -> service.updateRoleStatus(1L, actor, 2L, 1, -1L));
        assertThrows(IdentityAdministrationService.InvalidCommandException.class,
            () -> service.deleteRole(1L, actor, 2L, -1L));
        assertThrows(IdentityAdministrationService.InvalidCommandException.class,
            () -> service.updateDepartment(1L, actor, 2L, department, -1L));
        assertThrows(IdentityAdministrationService.InvalidCommandException.class,
            () -> service.deleteDepartment(1L, actor, 2L, -1L));
        assertThrows(IdentityAdministrationService.InvalidCommandException.class,
            () -> service.updateMenu(1L, actor, 2L, menu, -1L));
        assertThrows(IdentityAdministrationService.InvalidCommandException.class,
            () -> service.deleteMenu(1L, actor, 2L, -1L));
    }

    private static IdentityModels.Menu menu(long id, Long parentId) {
        return new IdentityModels.Menu(id, parentId, "menu", "Menu" + id, "/menu-" + id,
            "/system/user/list", null, null, "{\"title\":\"system.user.title\"}", 1, 0L);
    }

    private static IdentityAdministrationService serviceWith(IdentityQueryPort queries,
        DepartmentAdministrationPort departments, MenuAdministrationPort menus) {
        return new IdentityAdministrationService(queries, unsupported(UserAdministrationPort.class),
            unsupported(RoleAdministrationPort.class), departments, menus);
    }

    @SuppressWarnings("unchecked")
    private static <T> T returning(Class<T> type, String methodName, Object value) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
            (proxy, method, arguments) -> {
                if (method.getName().equals(methodName)) return value;
                throw new AssertionError("Unexpected call to " + type.getSimpleName() + "." + method.getName());
            });
    }

    @SuppressWarnings("unchecked")
    private static <T> T unsupported(Class<T> type) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
            (proxy, method, arguments) -> {
                throw new AssertionError("Validation unexpectedly called " + type.getSimpleName()
                    + "." + method.getName());
            });
    }
}
