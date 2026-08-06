package com.niv.payment.identity.oidc;

import com.niv.payment.identity.lifecycle.FederatedIdentity;
import com.niv.payment.identity.lifecycle.IdentityInvitationRelayRepository;
import com.niv.payment.identity.lifecycle.IdentityInvitationStep;
import com.niv.payment.identity.lifecycle.IdentityInvitationTask;
import com.niv.payment.permission.domain.AccountDomain;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class IdentityInvitationRelayTest {
    private static final Instant NOW = Instant.parse("2026-08-06T03:00:00Z");

    @Test
    void newIdentityExecutesEnableThenActionEmailBeforeApplicationActivation() {
        var calls = new ArrayList<String>();
        var repository = new RecordingRepository(task(FederatedIdentity.Mode.NEW_DISABLED,
            IdentityInvitationStep.KEYCLOAK_ENABLE));
        var relay = relay(repository, new RecordingKeycloak(calls));

        relay.runOnce();
        assertThat(calls).containsExactly("enable");
        assertThat(repository.completed).isEqualTo(IdentityInvitationStep.KEYCLOAK_ENABLE);

        repository.task = task(FederatedIdentity.Mode.NEW_DISABLED,
            IdentityInvitationStep.ACTION_EMAIL);
        relay.runOnce();
        assertThat(calls).containsExactly("enable", "email");
        assertThat(repository.completed).isEqualTo(IdentityInvitationStep.ACTION_EMAIL);

        repository.task = task(FederatedIdentity.Mode.NEW_DISABLED,
            IdentityInvitationStep.APPLICATION_ACTIVATION);
        relay.runOnce();
        assertThat(calls).containsExactly("enable", "email");
        assertThat(repository.completed).isEqualTo(IdentityInvitationStep.APPLICATION_ACTIVATION);
    }

    @Test
    void existingIdentityNeverChangesKeycloakCredentialsOrSendsAccountActions() {
        var calls = new ArrayList<String>();
        var repository = new RecordingRepository(task(FederatedIdentity.Mode.EXISTING_ACTIVE,
            IdentityInvitationStep.APPLICATION_ACTIVATION));

        relay(repository, new RecordingKeycloak(calls)).runOnce();

        assertThat(calls).isEmpty();
        assertThat(repository.completed).isEqualTo(IdentityInvitationStep.APPLICATION_ACTIVATION);
    }

    @Test
    void keycloakFailureIsRescheduledWithBoundedCode() {
        var repository = new RecordingRepository(task(FederatedIdentity.Mode.NEW_DISABLED,
            IdentityInvitationStep.KEYCLOAK_ENABLE));
        var relay = relay(repository, new RecordingKeycloak(new ArrayList<>()) {
            @Override
            public void enable(IdentityInvitationTask task) {
                throw new IdentityProvisioningException("KEYCLOAK_UNAVAILABLE");
            }
        });

        relay.runOnce();

        assertThat(repository.completed).isNull();
        assertThat(repository.errorCode).isEqualTo("KEYCLOAK_UNAVAILABLE");
        assertThat(repository.availableAt).isEqualTo(NOW.plusSeconds(15));
    }

    private static IdentityInvitationRelay relay(RecordingRepository repository,
                                                  IdentityInvitationRelay.KeycloakActions keycloak) {
        return new IdentityInvitationRelay(AccountDomain.MERCHANT, repository, keycloak,
            Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofSeconds(30), Duration.ofSeconds(15));
    }

    private static IdentityInvitationTask task(FederatedIdentity.Mode mode,
                                                IdentityInvitationStep nextStep) {
        return new IdentityInvitationTask(1, 2, 3, 4, 5, AccountDomain.MERCHANT,
            "https://idp.example.test/realms/MERCHANT", "subject-1", "invite-1", mode,
            "MEMBER", 1, nextStep);
    }

    private static final class RecordingRepository implements IdentityInvitationRelayRepository {
        private IdentityInvitationTask task;
        private IdentityInvitationStep completed;
        private Instant availableAt;
        private String errorCode;

        private RecordingRepository(IdentityInvitationTask task) {
            this.task = task;
        }

        @Override
        public Optional<IdentityInvitationTask> claimNext(AccountDomain accountDomain, Instant now,
                                                          Duration leaseDuration) {
            return Optional.ofNullable(task);
        }

        @Override
        public void completeStep(long invitationId, IdentityInvitationStep step, Instant completedAt) {
            completed = step;
        }

        @Override
        public void reschedule(long invitationId, Instant availableAt, String errorCode) {
            this.availableAt = availableAt;
            this.errorCode = errorCode;
        }
    }

    private static class RecordingKeycloak implements IdentityInvitationRelay.KeycloakActions {
        private final List<String> calls;

        private RecordingKeycloak(List<String> calls) {
            this.calls = calls;
        }

        @Override
        public void enable(IdentityInvitationTask task) {
            calls.add("enable");
        }

        @Override
        public void sendActionEmail(IdentityInvitationTask task) {
            calls.add("email");
        }
    }
}
