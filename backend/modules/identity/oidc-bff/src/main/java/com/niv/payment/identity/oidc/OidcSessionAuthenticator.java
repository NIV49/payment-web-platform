package com.niv.payment.identity.oidc;

import com.niv.payment.permission.domain.AccountDomain;
import com.niv.payment.permission.security.FederatedSessionPrincipal;
import com.niv.payment.permission.security.SaTokenSessionIssuer;

import java.util.Objects;
import java.util.Optional;

public final class OidcSessionAuthenticator implements OidcFlowService.SessionAuthenticator {
    private final AccountDomain accountDomain;
    private final IdentityRepository identities;
    private final SaTokenSessionIssuer sessions;

    public OidcSessionAuthenticator(AccountDomain accountDomain, IdentityRepository identities,
                                    SaTokenSessionIssuer sessions) {
        this.accountDomain = Objects.requireNonNull(accountDomain, "accountDomain");
        this.identities = Objects.requireNonNull(identities, "identities");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
    }

    @Override
    public void authenticate(OidcFlowService.TrustedEntry entry,
                             OidcFlowService.AuthenticatedIdentity identity) {
        IdentityAccount account = identities.findActive(
            accountDomain, entry.tenantId(), identity.issuer(), identity.subject())
            .filter(candidate -> candidate.accountDomain() == accountDomain)
            .orElseThrow(OidcFlowService.LoginRejectedException::new);
        sessions.loginFederated(new FederatedSessionPrincipal(
            account.userId(), account.membershipId(), account.tenantId(), account.departmentId(),
            account.permissionVersion(), account.sessionVersion(), account.identityVersion(), accountDomain,
            entry.entryHost(), identity.issuer(), identity.subject(), identity.sessionId(), identity.authTime(),
            identity.acr(), identity.idToken()));
    }

    @FunctionalInterface
    public interface IdentityRepository {
        Optional<IdentityAccount> findActive(AccountDomain accountDomain, long tenantId,
                                             String issuer, String subject);
    }

    public record IdentityAccount(long userId, long membershipId, long tenantId, Long departmentId,
                                  long permissionVersion, long sessionVersion, long identityVersion,
                                  AccountDomain accountDomain) { }
}
