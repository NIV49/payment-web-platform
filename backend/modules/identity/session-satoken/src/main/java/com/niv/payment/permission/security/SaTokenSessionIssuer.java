package com.niv.payment.permission.security;

import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpLogic;
import com.niv.payment.permission.domain.AccountDomain;
import com.niv.payment.permission.service.AuthenticationService;

import java.util.Objects;

public final class SaTokenSessionIssuer implements AuthenticationService.SessionIssuer {
    private final StpLogic stpLogic;
    private final AccountDomain accountDomain;

    public SaTokenSessionIssuer(StpLogic stpLogic, AccountDomain accountDomain) {
        this.stpLogic = Objects.requireNonNull(stpLogic, "stpLogic");
        this.accountDomain = Objects.requireNonNull(accountDomain, "accountDomain");
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
        session.set(SessionAttributeNames.STEP_UP_VERIFIED, false);
        return new AuthenticationService.LoginSession(stpLogic.getTokenValue());
    }

    @Override
    public void logout() {
        stpLogic.logout();
    }
}
