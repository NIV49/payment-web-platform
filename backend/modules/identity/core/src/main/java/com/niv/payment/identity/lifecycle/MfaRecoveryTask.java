package com.niv.payment.identity.lifecycle;

import com.niv.payment.permission.domain.AccountDomain;

import java.util.List;

public record MfaRecoveryTask(
    long recoveryId,
    long lifecycleEventRecordId,
    long userId,
    long tenantId,
    long targetMembershipId,
    AccountDomain accountDomain,
    String issuer,
    String subject,
    List<Long> membershipIds,
    int attempt,
    MfaRecoveryStep nextStep
) {
    public MfaRecoveryTask {
        if (recoveryId <= 0 || lifecycleEventRecordId <= 0 || userId <= 0
            || tenantId <= 0 || targetMembershipId <= 0) {
            throw new IllegalArgumentException("MFA recovery identifiers must be positive");
        }
        if (issuer == null || issuer.isBlank() || subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("MFA recovery identity is required");
        }
        membershipIds = List.copyOf(membershipIds);
        if (membershipIds.isEmpty() || membershipIds.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new IllegalArgumentException("MFA recovery memberships are invalid");
        }
        if (attempt <= 0) {
            throw new IllegalArgumentException("MFA recovery attempt must be positive");
        }
    }
}
