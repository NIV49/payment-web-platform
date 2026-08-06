package com.niv.payment.identity.oidc;

import com.niv.payment.identity.lifecycle.MfaRecoveryRepository;
import com.niv.payment.identity.lifecycle.MfaRecoveryStep;
import com.niv.payment.identity.lifecycle.MfaRecoveryTask;
import com.niv.payment.permission.domain.AccountDomain;
import com.niv.payment.permission.domain.AuthorizationSubject;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MfaRecoveryRelayTest {
    private static final Instant NOW = Instant.parse("2026-08-05T10:00:00Z");

    @Test
    void executesOnlyTheClaimedStepAndRecordsCompletion() {
        StubRepository repository = new StubRepository(task(MfaRecoveryStep.RECOVERY_CODES, 1));
        List<String> calls = new ArrayList<>();
        MfaRecoveryRelay relay = relay(repository, new RecordingKeycloak(calls),
            recovery -> calls.add("application"));

        assertThat(relay.runOnce()).isTrue();

        assertThat(calls).containsExactly("recovery-codes");
        assertThat(repository.completed).isEqualTo(MfaRecoveryStep.RECOVERY_CODES);
        assertThat(repository.errorCode).isNull();
    }

    @Test
    void failureLeavesRecoveryPendingWithBoundedBackoffAndNoCompletion() {
        StubRepository repository = new StubRepository(task(MfaRecoveryStep.MFA_CREDENTIALS, 3));
        MfaRecoveryRelay.KeycloakActions keycloak = new RecordingKeycloak(new ArrayList<>()) {
            @Override
            public void revokeMfaCredentials(MfaRecoveryTask task) {
                throw new KeycloakMfaRecoveryClient.MfaRecoveryActionException("KEYCLOAK_UNAVAILABLE");
            }
        };
        MfaRecoveryRelay relay = relay(repository, keycloak, recovery -> { });

        assertThat(relay.runOnce()).isTrue();

        assertThat(repository.completed).isNull();
        assertThat(repository.errorCode).isEqualTo("KEYCLOAK_UNAVAILABLE");
        assertThat(repository.availableAt).isEqualTo(NOW.plusSeconds(60));
    }

    @Test
    void returnsFalseWhenNoRecoveryIsReady() {
        StubRepository repository = new StubRepository(null);

        assertThat(relay(repository, new RecordingKeycloak(new ArrayList<>()), task -> { }).runOnce())
            .isFalse();
    }

    private static MfaRecoveryRelay relay(StubRepository repository,
                                          MfaRecoveryRelay.KeycloakActions keycloak,
                                          MfaRecoveryRelay.ApplicationSessionActions sessions) {
        return new MfaRecoveryRelay(AccountDomain.MERCHANT, repository, keycloak, sessions,
            Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofSeconds(30), Duration.ofSeconds(15));
    }

    private static MfaRecoveryTask task(MfaRecoveryStep step, int attempt) {
        return new MfaRecoveryTask(1, 2, 3, 4, 5, AccountDomain.MERCHANT,
            "https://idp.example.test/realms/MERCHANT", "subject-1", List.of(5L, 6L),
            attempt, step);
    }

    private static class RecordingKeycloak implements MfaRecoveryRelay.KeycloakActions {
        private final List<String> calls;

        RecordingKeycloak(List<String> calls) {
            this.calls = calls;
        }

        @Override
        public void revokeMfaCredentials(MfaRecoveryTask task) {
            calls.add("mfa-credentials");
        }

        @Override
        public void revokeRecoveryCodes(MfaRecoveryTask task) {
            calls.add("recovery-codes");
        }

        @Override
        public void revokeKeycloakSessions(MfaRecoveryTask task) {
            calls.add("keycloak-sessions");
        }
    }

    private static final class StubRepository implements MfaRecoveryRepository {
        private final MfaRecoveryTask task;
        private MfaRecoveryStep completed;
        private Instant availableAt;
        private String errorCode;

        private StubRepository(MfaRecoveryTask task) {
            this.task = task;
        }

        @Override
        public RecoveryRequest request(AccountDomain accountDomain, AuthorizationSubject actor,
                                       long targetMembershipId, UUID idempotencyKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<MfaRecoveryTask> claimNext(AccountDomain accountDomain, Instant now,
                                                   Duration leaseDuration) {
            return Optional.ofNullable(task);
        }

        @Override
        public void completeStep(long recoveryId, MfaRecoveryStep step, Instant completedAt) {
            completed = step;
        }

        @Override
        public void reschedule(long recoveryId, Instant availableAt, String errorCode) {
            this.availableAt = availableAt;
            this.errorCode = errorCode;
        }
    }
}
