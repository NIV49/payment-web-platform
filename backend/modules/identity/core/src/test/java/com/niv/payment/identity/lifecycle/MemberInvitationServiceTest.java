package com.niv.payment.identity.lifecycle;

import com.niv.payment.permission.domain.AccountDomain;
import com.niv.payment.permission.domain.AuthorizationSubject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemberInvitationServiceTest {
    private final RecordingRepository repository = new RecordingRepository();
    private final RecordingProvisioner provisioner = new RecordingProvisioner();
    private final MemberInvitationService service =
        new MemberInvitationService(AccountDomain.MERCHANT, repository, provisioner);

    @Test
    void requiresRecentStepUpBeforeReservingOrCallingKeycloak() {
        var actor = subject(false);

        assertThrows(MemberInvitationService.StepUpRequiredException.class,
            () -> service.invite(actor, command()));

        assertFalse(repository.reserved);
        assertFalse(provisioner.called);
    }

    @Test
    void rejectsMissingDuplicateOrInvalidRoleIdentifiersBeforeSideEffects() {
        assertThrows(IllegalArgumentException.class,
            () -> service.invite(subject(true), command(List.of())));
        assertThrows(IllegalArgumentException.class,
            () -> service.invite(subject(true), command(List.of(41L, 41L))));
        assertThrows(IllegalArgumentException.class,
            () -> service.invite(subject(true), command(List.of(0L))));

        assertFalse(repository.reserved);
        assertFalse(provisioner.called);
    }

    @Test
    void reservesBeforeCreatingDisabledRealmIdentityAndAttachesExactIssuerSubject() {
        var result = service.invite(subject(true), command());

        assertTrue(repository.reserved);
        assertTrue(provisioner.called);
        assertTrue(repository.attached);
        assertEquals(AccountDomain.MERCHANT, provisioner.domain);
        assertEquals("member@example.test", provisioner.email);
        assertEquals("Merchant Member", provisioner.displayName);
        assertEquals("https://idp.example.test/realms/MERCHANT", repository.identity.issuer());
        assertEquals("subject-501", repository.identity.subject());
        assertEquals(701L, result.invitationId());
        assertEquals(702L, result.membershipId());
        assertEquals(IdentityInvitationRepository.Status.PROVISION_PENDING, result.status());
    }

    private static MemberInvitationCommand command() {
        return command(List.of(41L));
    }

    private static MemberInvitationCommand command(List<Long> roleIds) {
        return new MemberInvitationCommand("member@example.test", "Merchant Member", roleIds,
            UUID.fromString("8ce154cf-4f13-4aac-b0de-74922513a14f"));
    }

    private static AuthorizationSubject subject(boolean stepUp) {
        return new AuthorizationSubject(11, 12, 13, null, 2, 3, stepUp);
    }

    private static final class RecordingRepository implements IdentityInvitationRepository {
        private boolean reserved;
        private boolean attached;
        private FederatedIdentity identity;

        @Override
        public Reservation reserveMember(AccountDomain accountDomain, AuthorizationSubject actor,
                                         MemberInvitationCommand command) {
            reserved = true;
            return new Reservation(701, actor.tenantId(), accountDomain, command.idempotencyKey(),
                command.displayName(), Status.RESERVED, null);
        }

        @Override
        public Invitation attachIdentity(Reservation reservation, FederatedIdentity identity) {
            attached = true;
            this.identity = identity;
            return new Invitation(reservation.invitationId(), 702, Status.PROVISION_PENDING);
        }
    }

    private static final class RecordingProvisioner implements IdentityProvisioningPort {
        private boolean called;
        private AccountDomain domain;
        private String email;
        private String displayName;

        @Override
        public FederatedIdentity resolveInvitationIdentity(AccountDomain accountDomain,
                                                           UUID idempotencyKey,
                                                           String email,
                                                           String displayName) {
            called = true;
            domain = accountDomain;
            this.email = email;
            this.displayName = displayName;
            return new FederatedIdentity("https://idp.example.test/realms/MERCHANT",
                "subject-501", "invite-8ce154cf-4f13-4aac-b0de-74922513a14f");
        }
    }
}
