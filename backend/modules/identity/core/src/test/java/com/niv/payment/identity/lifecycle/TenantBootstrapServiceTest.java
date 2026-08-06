package com.niv.payment.identity.lifecycle;

import com.niv.payment.permission.domain.AccountDomain;
import com.niv.payment.permission.domain.AuthorizationSubject;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TenantBootstrapServiceTest {
    private final RecordingBootstrapRepository repository = new RecordingBootstrapRepository();
    private final RecordingProvisioner provisioner = new RecordingProvisioner();
    private final TenantBootstrapService service = new TenantBootstrapService(repository, provisioner);

    @Test
    void requiresRecentStepUpBeforeTenantReservation() {
        assertThrows(TenantBootstrapService.StepUpRequiredException.class,
            () -> service.bootstrap(platformActor(false), command(TenantType.AGENT)));

        assertFalse(repository.reserved);
        assertFalse(provisioner.called);
    }

    @Test
    void rejectsPlatformTenantType() {
        assertThrows(IllegalArgumentException.class,
            () -> service.bootstrap(platformActor(true), command(TenantType.PLATFORM)));

        assertFalse(repository.reserved);
        assertFalse(provisioner.called);
    }

    @Test
    void derivesAgentRealmFromTenantTypeAndNeverFromClientInput() {
        var result = service.bootstrap(platformActor(true), command(TenantType.AGENT));

        assertEquals(AccountDomain.AGENT, repository.targetDomain);
        assertEquals(AccountDomain.AGENT, provisioner.domain);
        assertEquals(901L, result.tenantId());
        assertEquals(902L, result.firstAdministratorMembershipId());
        assertEquals(IdentityInvitationRepository.Status.PROVISION_PENDING, result.status());
    }

    @Test
    void derivesMerchantRealmForBothMerchantTenantTypes() {
        service.bootstrap(platformActor(true), command(TenantType.DIRECT_MERCHANT));
        assertEquals(AccountDomain.MERCHANT, provisioner.domain);

        repository.reset();
        provisioner.reset();
        service.bootstrap(platformActor(true), command(TenantType.INDIRECT_MERCHANT));
        assertEquals(AccountDomain.MERCHANT, provisioner.domain);
    }

    private static TenantBootstrapCommand command(TenantType tenantType) {
        return new TenantBootstrapCommand("tenant-acme", "Acme", tenantType,
            "acme.example.test", "admin@example.test", "Acme Administrator",
            UUID.fromString("8ce154cf-4f13-4aac-b0de-74922513a14f"));
    }

    private static AuthorizationSubject platformActor(boolean stepUp) {
        return new AuthorizationSubject(1, 2, 3, null, 4, 5, stepUp);
    }

    private static final class RecordingBootstrapRepository implements TenantBootstrapRepository {
        private boolean reserved;
        private AccountDomain targetDomain;

        @Override
        public TenantReservation reserve(AuthorizationSubject actor, AccountDomain accountDomain,
                                         TenantBootstrapCommand command) {
            reserved = true;
            targetDomain = accountDomain;
            return new TenantReservation(901, new IdentityInvitationRepository.Reservation(903,
                901, accountDomain, command.idempotencyKey(), command.firstAdministratorDisplayName(),
                IdentityInvitationRepository.Status.RESERVED, null));
        }

        @Override
        public TenantBootstrap attachIdentity(TenantReservation reservation,
                                              FederatedIdentity identity) {
            return new TenantBootstrap(reservation.tenantId(), 903, 902,
                IdentityInvitationRepository.Status.PROVISION_PENDING);
        }

        private void reset() {
            reserved = false;
            targetDomain = null;
        }
    }

    private static final class RecordingProvisioner implements IdentityProvisioningPort {
        private boolean called;
        private AccountDomain domain;

        @Override
        public FederatedIdentity resolveInvitationIdentity(AccountDomain accountDomain,
                                                           UUID idempotencyKey,
                                                           String email,
                                                           String displayName) {
            called = true;
            domain = accountDomain;
            return new FederatedIdentity("https://idp.example.test/realms/" + accountDomain,
                "subject-" + accountDomain, "invite-" + idempotencyKey);
        }

        private void reset() {
            called = false;
            domain = null;
        }
    }
}
