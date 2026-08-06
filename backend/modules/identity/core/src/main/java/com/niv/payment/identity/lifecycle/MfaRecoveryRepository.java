package com.niv.payment.identity.lifecycle;

import com.niv.payment.permission.domain.AccountDomain;
import com.niv.payment.permission.domain.AuthorizationSubject;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface MfaRecoveryRepository {
    RecoveryRequest request(AccountDomain accountDomain, AuthorizationSubject actor,
                            long targetMembershipId, UUID idempotencyKey);

    Optional<MfaRecoveryTask> claimNext(AccountDomain accountDomain, Instant now, Duration leaseDuration);

    void completeStep(long recoveryId, MfaRecoveryStep step, Instant completedAt);

    void reschedule(long recoveryId, Instant availableAt, String errorCode);

    record RecoveryRequest(long recoveryId, Status status) {
        public RecoveryRequest {
            if (recoveryId <= 0) {
                throw new IllegalArgumentException("MFA recovery id must be positive");
            }
        }
    }

    enum Status {
        RECOVERY_PENDING,
        COMPLETED
    }
}
