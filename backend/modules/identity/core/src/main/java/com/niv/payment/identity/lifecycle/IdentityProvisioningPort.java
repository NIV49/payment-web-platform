package com.niv.payment.identity.lifecycle;

import com.niv.payment.permission.domain.AccountDomain;

import java.util.UUID;

public interface IdentityProvisioningPort {
    FederatedIdentity resolveInvitationIdentity(AccountDomain accountDomain, UUID idempotencyKey,
                                                String email, String displayName);
}
