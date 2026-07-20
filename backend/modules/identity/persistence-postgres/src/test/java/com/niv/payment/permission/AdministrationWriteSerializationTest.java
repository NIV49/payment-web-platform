package com.niv.payment.permission;

import com.niv.payment.permission.persistence.mapper.IdentityAdminMapper;
import com.niv.payment.permission.persistence.repository.MyBatisIdentityAdministrationRepository;
import com.niv.payment.permission.service.IdentityModels;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AdministrationWriteSerializationTest {
    @Test
    void treeParentChecksRunOnlyAfterTheTenantWriteLock() {
        List<String> calls = new ArrayList<>();
        IdentityAdminMapper mapper = mapper(calls);
        var repository = new MyBatisIdentityAdministrationRepository(mapper, () -> "test-trace");

        repository.updateDepartment(1L, 1000L, 10L,
            new IdentityModels.DepartmentCommand(null, "Operations", 1, null));
        assertTrue(calls.indexOf("lockTenantForAdministration")
            < calls.indexOf("departmentParentAllowed"));

        calls.clear();
        repository.updateMenu(1L, 1000L, 20L,
            new IdentityModels.MenuCommand(null, "catalog", "System", "/system", null,
                null, null, "{\"title\":\"system.title\"}", 1));
        assertTrue(calls.indexOf("lockTenantForAdministration")
            < calls.indexOf("menuParentAllowed"));
    }

    @Test
    void roleReplacementRunsOnlyAfterTheTenantWriteLock() {
        List<String> calls = new ArrayList<>();
        var repository = new MyBatisIdentityAdministrationRepository(mapper(calls), () -> "test-trace");

        repository.updateRole(1L, 1000L, 2001L,
            new IdentityModels.RoleCommand("Auditor", List.of(), 1, null));

        assertTrue(calls.indexOf("lockTenantForAdministration")
            < calls.indexOf("updateRole"));
    }

    private static IdentityAdminMapper mapper(List<String> calls) {
        return (IdentityAdminMapper) Proxy.newProxyInstance(
            IdentityAdminMapper.class.getClassLoader(),
            new Class<?>[]{IdentityAdminMapper.class},
            (proxy, method, args) -> {
                calls.add(method.getName());
                return switch (method.getName()) {
                    case "isActivePlatformTenant", "departmentParentAllowed", "menuParentAllowed" -> true;
                    case "lockTenantForAdministration" -> 1L;
                    case "updateDepartment", "updateMenu", "updateRole", "insertAudit",
                         "deleteRoleMenus", "bumpRoleMembers" -> 1;
                    default -> defaultValue(method.getReturnType());
                };
            });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == long.class) return 0L;
        if (type == int.class) return 0;
        throw new IllegalStateException("Unsupported primitive return type: " + type);
    }
}
