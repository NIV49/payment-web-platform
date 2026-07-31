package com.niv.payment.permission;

import com.niv.payment.permission.service.RoleAssignmentPolicy;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RoleAssignmentPolicyTest {
    private static final RoleAssignmentPolicy POLICY = new RoleAssignmentPolicy();
    private static final Map<Long, RoleAssignmentPolicy.RoleFacts> ROLES = Map.of(
        1L, new RoleAssignmentPolicy.RoleFacts(1L, false, true, true),
        2L, new RoleAssignmentPolicy.RoleFacts(2L, true, false, true),
        3L, new RoleAssignmentPolicy.RoleFacts(3L, true, false, true),
        4L, new RoleAssignmentPolicy.RoleFacts(4L, false, true, false),
        5L, new RoleAssignmentPolicy.RoleFacts(5L, true, false, false));

    @Test
    void ordinaryWorkflowCannotAddOrRemoveSystemRoles() {
        assertThrows(RoleAssignmentPolicy.RoleNotAssignableException.class,
            () -> POLICY.validateReplacement(10, 20, Set.of(), Set.of(1L), Set.of(1L), ROLES));
        assertThrows(RoleAssignmentPolicy.RoleNotAssignableException.class,
            () -> POLICY.validateReplacement(10, 20, Set.of(1L), Set.of(), Set.of(1L), ROLES));
    }

    @Test
    void userCannotAddRolesToThemselves() {
        assertThrows(RoleAssignmentPolicy.RoleNotAssignableException.class,
            () -> POLICY.validateReplacement(10, 10, Set.of(), Set.of(2L), Set.of(1L), ROLES));
    }

    @Test
    void nonSystemAdministratorCannotDelegateRolesTheyDoNotHold() {
        assertThrows(RoleAssignmentPolicy.RoleNotAssignableException.class,
            () -> POLICY.validateReplacement(10, 20, Set.of(), Set.of(3L), Set.of(2L), ROLES));
    }

    @Test
    void nonSystemAdministratorCannotRemoveRolesTheyDoNotHold() {
        assertThrows(RoleAssignmentPolicy.RoleNotAssignableException.class,
            () -> POLICY.validateReplacement(10, 20, Set.of(3L), Set.of(), Set.of(2L), ROLES));
    }

    @Test
    void nonSystemAdministratorCanRemoveADelegableRoleTheyHold() {
        assertDoesNotThrow(
            () -> POLICY.validateReplacement(10, 20, Set.of(2L), Set.of(), Set.of(2L), ROLES));
    }

    @Test
    void systemAdministratorCanAssignNormalRoles() {
        assertDoesNotThrow(
            () -> POLICY.validateReplacement(10, 20, Set.of(), Set.of(2L), Set.of(1L), ROLES));
    }

    @Test
    void disabledSystemRoleDoesNotGrantDelegationBypass() {
        assertThrows(RoleAssignmentPolicy.RoleNotAssignableException.class,
            () -> POLICY.validateReplacement(10, 20, Set.of(), Set.of(2L), Set.of(4L), ROLES));
    }

    @Test
    void activeSystemAdministratorCanRemoveDisabledNormalRoleForCleanup() {
        assertDoesNotThrow(
            () -> POLICY.validateReplacement(10, 20, Set.of(5L), Set.of(), Set.of(1L), ROLES));
    }

    @Test
    void nonSystemAdministratorCannotRemoveDisabledNormalRoleTheyHold() {
        assertThrows(RoleAssignmentPolicy.RoleNotAssignableException.class,
            () -> POLICY.validateReplacement(10, 20, Set.of(5L), Set.of(), Set.of(5L), ROLES));
    }

    @Test
    void lastSystemAdministratorCannotBeDeactivated() {
        assertThrows(RoleAssignmentPolicy.LastAdministratorException.class,
            () -> POLICY.validateDeactivation(true, false));
        assertDoesNotThrow(() -> POLICY.validateDeactivation(true, true));
    }
}
