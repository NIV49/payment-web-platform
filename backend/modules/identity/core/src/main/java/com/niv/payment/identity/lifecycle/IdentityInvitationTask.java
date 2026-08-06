package com.niv.payment.identity.lifecycle;

import com.niv.payment.permission.domain.AccountDomain;

public record IdentityInvitationTask(
    long invitationId,
    long lifecycleEventRecordId,
    long userId,
    long tenantId,
    long membershipId,
    AccountDomain accountDomain,
    String issuer,
    String subject,
    String username,
    FederatedIdentity.Mode mode,
    String invitationKind,
    int attempt,
    IdentityInvitationStep nextStep
) {
    public IdentityInvitationTask {
        if (invitationId <= 0 || lifecycleEventRecordId <= 0 || userId <= 0 || tenantId <= 0
            || membershipId <= 0 || accountDomain == null || issuer == null || issuer.isBlank()
            || subject == null || subject.isBlank() || username == null || username.isBlank()
            || mode == null || invitationKind == null || invitationKind.isBlank()
            || attempt <= 0 || nextStep == null) {
            throw new IllegalArgumentException("Identity invitation task is invalid");
        }
    }
}
