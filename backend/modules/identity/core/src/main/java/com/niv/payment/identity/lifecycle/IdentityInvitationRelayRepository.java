package com.niv.payment.identity.lifecycle;

import com.niv.payment.permission.domain.AccountDomain;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

public interface IdentityInvitationRelayRepository {
    Optional<IdentityInvitationTask> claimNext(AccountDomain accountDomain, Instant now,
                                               Duration leaseDuration);

    void completeStep(long invitationId, IdentityInvitationStep step, Instant completedAt);

    void reschedule(long invitationId, Instant availableAt, String errorCode);
}
