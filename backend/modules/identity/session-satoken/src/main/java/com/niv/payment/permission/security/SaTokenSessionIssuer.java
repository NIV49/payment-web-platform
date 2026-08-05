package com.niv.payment.permission.security;

import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpLogic;
import com.niv.payment.permission.domain.AccountDomain;
import com.niv.payment.permission.service.AuthenticationService;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;

public final class SaTokenSessionIssuer implements AuthenticationService.SessionIssuer {
    private final StpLogic stpLogic;
    private final AccountDomain accountDomain;
    private final SecureRandom random;

    public SaTokenSessionIssuer(StpLogic stpLogic, AccountDomain accountDomain) {
        this(stpLogic, accountDomain, new SecureRandom());
    }

    SaTokenSessionIssuer(StpLogic stpLogic, AccountDomain accountDomain, SecureRandom random) {
        this.stpLogic = Objects.requireNonNull(stpLogic, "stpLogic");
        this.accountDomain = Objects.requireNonNull(accountDomain, "accountDomain");
        this.random = Objects.requireNonNull(random, "random");
    }

    @Override
    public AuthenticationService.LoginSession login(AuthenticationService.CredentialAccount account) {
        if (account.accountDomain() != accountDomain) {
            throw new IllegalArgumentException("Credential account domain does not match the session realm");
        }
        stpLogic.login(account.membershipId());
        SaSession session = stpLogic.getSession();
        session.set(SessionAttributeNames.ACCOUNT_DOMAIN, accountDomain.name());
        session.set(SessionAttributeNames.USER_ID, account.userId());
        session.set(SessionAttributeNames.MEMBERSHIP_ID, account.membershipId());
        session.set(SessionAttributeNames.TENANT_ID, account.tenantId());
        session.set(SessionAttributeNames.DEPARTMENT_ID, account.departmentId());
        session.set(SessionAttributeNames.PERMISSION_VERSION, account.permissionVersion());
        session.set(SessionAttributeNames.SESSION_VERSION, account.sessionVersion());
        session.set(SessionAttributeNames.IDENTITY_VERSION, account.identityVersion());
        session.set(SessionAttributeNames.STEP_UP_VERIFIED, false);
        session.set(SessionAttributeNames.REQUEST_PROOF, newRequestProof());
        return new AuthenticationService.LoginSession(stpLogic.getTokenValue());
    }

    public AuthenticationService.LoginSession loginFederated(FederatedSessionPrincipal principal) {
        if (principal.accountDomain() != accountDomain) {
            throw new IllegalArgumentException("Federated account domain does not match the session realm");
        }
        stpLogic.login(principal.membershipId());
        SaSession session = stpLogic.getSession();
        session.set(SessionAttributeNames.ACCOUNT_DOMAIN, accountDomain.name());
        session.set(SessionAttributeNames.USER_ID, principal.userId());
        session.set(SessionAttributeNames.MEMBERSHIP_ID, principal.membershipId());
        session.set(SessionAttributeNames.TENANT_ID, principal.tenantId());
        session.set(SessionAttributeNames.DEPARTMENT_ID, principal.departmentId());
        session.set(SessionAttributeNames.PERMISSION_VERSION, principal.permissionVersion());
        session.set(SessionAttributeNames.SESSION_VERSION, principal.sessionVersion());
        session.set(SessionAttributeNames.IDENTITY_VERSION, principal.identityVersion());
        session.set(SessionAttributeNames.ENTRY_HOST, principal.entryHost());
        session.set(SessionAttributeNames.ISSUER, principal.issuer());
        session.set(SessionAttributeNames.SUBJECT, principal.subject());
        session.set(SessionAttributeNames.OIDC_SESSION_ID, principal.oidcSessionId());
        session.set(SessionAttributeNames.AUTH_TIME, principal.authTime().getEpochSecond());
        session.set(SessionAttributeNames.ACR, principal.acr());
        session.set(SessionAttributeNames.OIDC_ID_ASSERTION, principal.idToken());
        session.set(SessionAttributeNames.STEP_UP_AT, null);
        session.set(SessionAttributeNames.STEP_UP_VERIFIED, false);
        session.set(SessionAttributeNames.REQUEST_PROOF, newRequestProof());
        return new AuthenticationService.LoginSession(stpLogic.getTokenValue());
    }

    @Override
    public void logout() {
        stpLogic.logout();
    }

    private String newRequestProof() {
        byte[] value = new byte[32];
        random.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }
}
