package com.niv.payment.identity.lifecycle;

import com.niv.payment.permission.domain.AccountDomain;
import com.niv.payment.permission.domain.AuthorizationSubject;

import java.util.Objects;

public final class MemberInvitationService {
    private final AccountDomain accountDomain;
    private final IdentityInvitationRepository repository;
    private final IdentityProvisioningPort provisioner;

    public MemberInvitationService(AccountDomain accountDomain, IdentityInvitationRepository repository,
                                   IdentityProvisioningPort provisioner) {
        this.accountDomain = Objects.requireNonNull(accountDomain, "accountDomain");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.provisioner = Objects.requireNonNull(provisioner, "provisioner");
    }

    public IdentityInvitationRepository.Invitation invite(AuthorizationSubject actor,
                                                          MemberInvitationCommand command) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(command, "command");
        if (!actor.stepUpVerified()) {
            throw new StepUpRequiredException();
        }
        IdentityInvitationRepository.Reservation reservation =
            repository.reserveMember(accountDomain, actor, command);
        FederatedIdentity identity = provisioner.resolveInvitationIdentity(accountDomain,
            command.idempotencyKey(), command.email(), command.displayName());
        return repository.attachIdentity(reservation, identity);
    }

    public static final class StepUpRequiredException extends RuntimeException {
        public StepUpRequiredException() {
            super("A recent LoA 2 step-up is required");
        }
    }
}
