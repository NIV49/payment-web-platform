package com.niv.payment.permission;

import com.niv.payment.permission.domain.PermissionCode;
import com.niv.payment.permission.domain.PermissionDefinition;
import com.niv.payment.permission.domain.PermissionGrant;
import com.niv.payment.permission.domain.RiskLevel;
import com.niv.payment.permission.service.RoleGrantAdministrationService;
import com.niv.payment.permission.service.RoleGrantChangeCommand;
import com.niv.payment.permission.service.RoleGrantWritePort;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoleGrantAdministrationServiceTest {

    @Test
    void rejectsFundCatalogEntryThatDoesNotExplicitlyRequireStepUp() {
        PermissionGrant invalid = new PermissionGrant(1L, 2L, PermissionCode.of("payout:approve"), RiskLevel.FUND,
            Set.of(), List.of(), false, true, true);
        RoleGrantAdministrationService service = new RoleGrantAdministrationService(command -> {
            throw new AssertionError("Invalid change must not reach persistence");
        }, code -> new PermissionDefinition(code, RiskLevel.FUND, Set.of(), true, true, true));

        assertThrows(IllegalArgumentException.class, () -> service.replace(
            new RoleGrantChangeCommand(3L, 2L, 4L, 5L, List.of(invalid))));
    }

    @Test
    void delegatesValidatedReplacementToOneAtomicWriteBoundary() {
        AtomicBoolean called = new AtomicBoolean();
        RoleGrantWritePort port = command -> called.set(true);
        PermissionGrant normal = new PermissionGrant(1L, 2L, PermissionCode.of("order:view"), RiskLevel.NORMAL,
            Set.of(), List.of(), false, false, true);

        new RoleGrantAdministrationService(port,
            code -> new PermissionDefinition(code, RiskLevel.NORMAL, Set.of(), false, false, true)).replace(
            new RoleGrantChangeCommand(3L, 2L, 4L, 5L, List.of(normal)));

        assertTrue(called.get());
    }

    @Test
    void rejectsAnAttemptToDowngradeCatalogRiskMetadata() {
        PermissionGrant forged = new PermissionGrant(1L, 2L, PermissionCode.of("payout:approve"), RiskLevel.NORMAL,
            Set.of(), List.of(), false, false, true);
        var service = new RoleGrantAdministrationService(command -> {
            throw new AssertionError("Forged metadata must not reach persistence");
        }, code -> new PermissionDefinition(code, RiskLevel.FUND, Set.of(), true, true, true));

        assertThrows(IllegalArgumentException.class, () -> service.replace(
            new RoleGrantChangeCommand(3L, 2L, 4L, 5L, List.of(forged))));
    }
}
