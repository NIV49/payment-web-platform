package com.niv.payment.permission;

import com.niv.payment.permission.domain.AccountDomain;
import com.niv.payment.permission.domain.AdministrationActor;
import com.niv.payment.permission.port.PlatformUserGovernancePort;
import com.niv.payment.permission.service.IdentityAdministrationService;
import com.niv.payment.permission.service.IdentityModels;
import com.niv.payment.permission.service.PlatformUserGovernanceService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PlatformUserGovernanceServiceTest {
    private final RecordingPort port = new RecordingPort();
    private final PlatformUserGovernanceService service = new PlatformUserGovernanceService(port);
    private final AdministrationActor actor = new AdministrationActor(1000, 100, 3, 0);

    @Test
    void normalizesEmailAndAllowsOnlyMerchantOrAgentAdministratorCreation() {
        long id = service.createTenantAdministrator(1, actor,
            new PlatformUserGovernancePort.CreateAdministratorCommand(
                AccountDomain.MERCHANT, 2, " Admin@Merchant.Example ", "Merchant Admin", 1));

        assertEquals(200L, id);
        assertEquals("admin@merchant.example", port.created.email());
        assertEquals(AccountDomain.MERCHANT, port.created.accountDomain());
    }

    @Test
    void rejectsPlatformAsCrossDomainWriteTarget() {
        assertThrows(IdentityAdministrationService.InvalidCommandException.class,
            () -> service.createTenantAdministrator(1, actor,
            new PlatformUserGovernancePort.CreateAdministratorCommand(
                AccountDomain.PLATFORM, 1, "admin@platform.example", "Platform", 1)));
    }

    @Test
    void validatesDirectoryPagingBeforeCallingThePort() {
        assertThrows(IdentityAdministrationService.InvalidCommandException.class,
            () -> service.findUsers(1, actor,
            new PlatformUserGovernancePort.DirectoryQuery(
                AccountDomain.PLATFORM, null, null, null, null, null, 1, 201)));
    }

    @Test
    void allowsDepartmentFilterOnlyForThePlatformDirectory() {
        service.findUsers(1, actor, new PlatformUserGovernancePort.DirectoryQuery(
            AccountDomain.PLATFORM, null, 10L, null, null, null, 1, 20));

        assertEquals(10L, port.directoryQuery.departmentId());
        assertThrows(IdentityAdministrationService.InvalidCommandException.class,
            () -> service.findUsers(1, actor,
                new PlatformUserGovernancePort.DirectoryQuery(
                    AccountDomain.MERCHANT, 2L, 10L, null, null, null, 1, 20)));
        assertThrows(IdentityAdministrationService.InvalidCommandException.class,
            () -> service.findUsers(1, actor,
                new PlatformUserGovernancePort.DirectoryQuery(
                    AccountDomain.PLATFORM, null, 0L, null, null, null, 1, 20)));
    }

    @Test
    void validatesAndForwardsABoundCrossDomainPasswordReset() {
        var result = service.resetUserPassword(1, actor, 200,
            new PlatformUserGovernancePort.PasswordResetCommand(
                AccountDomain.AGENT, 3, 7, "Abcd1234Efgh!!!!"));

        assertEquals(8L, result.credentialVersion());
        assertEquals(AccountDomain.AGENT, port.passwordReset.accountDomain());
        assertEquals(3L, port.passwordReset.tenantId());
        assertEquals("Abcd1234Efgh!!!!", port.passwordReset.password());
        assertFalse(port.passwordReset.toString().contains("Abcd1234Efgh!!!!"));
    }

    @Test
    void rejectsPlatformAndMalformedPasswordsBeforeCrossDomainPersistence() {
        assertThrows(IdentityAdministrationService.InvalidCommandException.class,
            () -> service.resetUserPassword(1, actor, 200,
                new PlatformUserGovernancePort.PasswordResetCommand(
                    AccountDomain.PLATFORM, 1, 0, "Abcd1234Efgh!!!!")));
        assertThrows(IdentityAdministrationService.InvalidCommandException.class,
            () -> service.resetUserPassword(1, actor, 200,
                new PlatformUserGovernancePort.PasswordResetCommand(
                    AccountDomain.MERCHANT, 2, 0, "password")));
    }

    private static final class RecordingPort implements PlatformUserGovernancePort {
        private CreateAdministratorCommand created;
        private PasswordResetCommand passwordReset;
        private DirectoryQuery directoryQuery;

        @Override
        public IdentityModels.Page<DirectoryUser> findUsers(
            long sourceTenantId, AdministrationActor actor, DirectoryQuery query) {
            directoryQuery = query;
            return new IdentityModels.Page<>(List.of(), 0);
        }

        @Override
        public List<TenantOption> findTargetTenants(
            long sourceTenantId, AdministrationActor actor, AccountDomain accountDomain) {
            return List.of();
        }

        @Override
        public long createTenantAdministrator(
            long sourceTenantId, AdministrationActor actor, CreateAdministratorCommand command) {
            created = command;
            return 200;
        }

        @Override
        public void updateTenantAdministrator(
            long sourceTenantId, AdministrationActor actor, long userId,
            UpdateAdministratorCommand command) {
        }

        @Override
        public IdentityModels.PasswordResetResult resetUserPassword(
            long sourceTenantId, AdministrationActor actor, long userId,
            PasswordResetCommand command) {
            passwordReset = command;
            return new IdentityModels.PasswordResetResult(8, 4, 5);
        }
    }
}
