package com.niv.payment.identity.lifecycle;

import com.niv.payment.permission.domain.AccountDomain;
import com.niv.payment.permission.domain.AuthorizationSubject;

import java.util.Objects;

public final class TenantBootstrapService {
    private final TenantBootstrapRepository repository;
    private final IdentityProvisioningPort provisioner;

    public TenantBootstrapService(TenantBootstrapRepository repository,
                                  IdentityProvisioningPort provisioner) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.provisioner = Objects.requireNonNull(provisioner, "provisioner");
    }

    public TenantBootstrapRepository.TenantBootstrap bootstrap(AuthorizationSubject actor,
                                                               TenantBootstrapCommand command) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(command, "command");
        if (!actor.stepUpVerified()) {
            throw new StepUpRequiredException();
        }
        if (command.tenantType() == TenantType.PLATFORM) {
            throw new IllegalArgumentException("Platform tenant bootstrap is not supported");
        }
        AccountDomain targetDomain = command.tenantType().accountDomain();
        TenantBootstrapRepository.TenantReservation reservation =
            repository.reserve(actor, targetDomain, command);
        FederatedIdentity identity = provisioner.resolveInvitationIdentity(targetDomain,
            command.idempotencyKey(), command.firstAdministratorEmail(),
            command.firstAdministratorDisplayName());
        return repository.attachIdentity(reservation, identity);
    }

    public static final class StepUpRequiredException extends RuntimeException {
        public StepUpRequiredException() {
            super("A recent LoA 2 step-up is required");
        }
    }
}
