package com.niv.payment.identity.lifecycle;

import com.niv.payment.permission.domain.AccountDomain;
import com.niv.payment.permission.domain.AuthorizationSubject;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;

class MfaRecoveryServiceTest {
    private final StubRepository repository = new StubRepository();
    private final MfaRecoveryService service = new MfaRecoveryService(AccountDomain.MERCHANT, repository);

    @Test
    void requiresRecentStepUpBeforeWritingARecoveryRequest() {
        AuthorizationSubject actor = new AuthorizationSubject(1, 2, 3, null, 0, 0, false);

        assertThrows(MfaRecoveryService.StepUpRequiredException.class,
            () -> service.request(actor, 4, UUID.randomUUID()));
    }

    @Test
    void rejectsSelfRecoveryEvenAfterStepUp() {
        AuthorizationSubject actor = new AuthorizationSubject(1, 2, 3, null, 0, 0, true);

        assertThrows(IllegalArgumentException.class,
            () -> service.request(actor, 2, UUID.randomUUID()));
    }

    private static final class StubRepository implements MfaRecoveryRepository {
        @Override
        public RecoveryRequest request(AccountDomain accountDomain, AuthorizationSubject actor,
                                       long targetMembershipId, UUID idempotencyKey) {
            throw new AssertionError("Repository must not be called for a rejected request");
        }

        @Override
        public Optional<MfaRecoveryTask> claimNext(AccountDomain accountDomain, Instant now,
                                                   Duration leaseDuration) {
            return Optional.empty();
        }

        @Override
        public void completeStep(long recoveryId, MfaRecoveryStep step, Instant completedAt) { }

        @Override
        public void reschedule(long recoveryId, Instant availableAt, String errorCode) { }
    }
}
