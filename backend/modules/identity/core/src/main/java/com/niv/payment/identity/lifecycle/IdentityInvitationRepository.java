package com.niv.payment.identity.lifecycle;

import com.niv.payment.permission.domain.AccountDomain;
import com.niv.payment.permission.domain.AuthorizationSubject;

import java.util.UUID;

public interface IdentityInvitationRepository {
    Reservation reserveMember(AccountDomain accountDomain, AuthorizationSubject actor,
                              MemberInvitationCommand command);

    Invitation attachIdentity(Reservation reservation, FederatedIdentity identity);

    record Reservation(long invitationId, long tenantId, AccountDomain accountDomain,
                       UUID idempotencyKey, String displayName, Status status, Long membershipId) {
        public Reservation {
            if (invitationId <= 0 || tenantId <= 0 || accountDomain == null || idempotencyKey == null
                || displayName == null || displayName.isBlank() || status == null
                || (membershipId != null && membershipId <= 0)) {
                throw new IllegalArgumentException("Identity invitation reservation is invalid");
            }
        }
    }

    record Invitation(long invitationId, long membershipId, Status status) {
        public Invitation {
            if (invitationId <= 0 || membershipId <= 0 || status == null) {
                throw new IllegalArgumentException("Identity invitation is invalid");
            }
        }
    }

    enum Status {
        RESERVED,
        PROVISION_PENDING,
        COMPLETED
    }
}
