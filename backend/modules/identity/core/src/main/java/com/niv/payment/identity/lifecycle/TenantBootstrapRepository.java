package com.niv.payment.identity.lifecycle;

import com.niv.payment.permission.domain.AccountDomain;
import com.niv.payment.permission.domain.AuthorizationSubject;

public interface TenantBootstrapRepository {
    TenantReservation reserve(AuthorizationSubject actor, AccountDomain accountDomain,
                              TenantBootstrapCommand command);

    TenantBootstrap attachIdentity(TenantReservation reservation, FederatedIdentity identity);

    record TenantReservation(long tenantId, IdentityInvitationRepository.Reservation invitation) {
        public TenantReservation {
            if (tenantId <= 0 || invitation == null || tenantId != invitation.tenantId()) {
                throw new IllegalArgumentException("Tenant bootstrap reservation is invalid");
            }
        }
    }

    record TenantBootstrap(long tenantId, long invitationId, long firstAdministratorMembershipId,
                           IdentityInvitationRepository.Status status) {
        public TenantBootstrap {
            if (tenantId <= 0 || invitationId <= 0 || firstAdministratorMembershipId <= 0
                || status == null) {
                throw new IllegalArgumentException("Tenant bootstrap result is invalid");
            }
        }
    }
}
