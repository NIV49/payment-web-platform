package com.niv.payment.identity.lifecycle;

import com.niv.payment.permission.domain.AccountDomain;
import com.niv.payment.permission.domain.AuthorizationSubject;

import java.util.Objects;
import java.util.UUID;

public final class MfaRecoveryService {
    private final AccountDomain accountDomain;
    private final MfaRecoveryRepository repository;

    public MfaRecoveryService(AccountDomain accountDomain, MfaRecoveryRepository repository) {
        this.accountDomain = Objects.requireNonNull(accountDomain, "accountDomain");
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    public MfaRecoveryRepository.RecoveryRequest request(AuthorizationSubject actor,
                                                         long targetMembershipId,
                                                         UUID idempotencyKey) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        if (!actor.stepUpVerified()) {
            throw new StepUpRequiredException();
        }
        if (targetMembershipId <= 0 || targetMembershipId == actor.membershipId()) {
            throw new IllegalArgumentException("MFA recovery requires another membership in the tenant");
        }
        return repository.request(accountDomain, actor, targetMembershipId, idempotencyKey);
    }

    public static final class StepUpRequiredException extends RuntimeException {
        public StepUpRequiredException() {
            super("A recent LoA 2 step-up is required");
        }
    }
}
