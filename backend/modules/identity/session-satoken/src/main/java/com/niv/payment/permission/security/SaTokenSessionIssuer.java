package com.niv.payment.permission.security;

import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpUtil;
import com.niv.payment.permission.service.AuthenticationService;

public final class SaTokenSessionIssuer implements AuthenticationService.SessionIssuer {
    @Override
    public AuthenticationService.LoginSession login(AuthenticationService.CredentialAccount account) {
        StpUtil.login(account.membershipId());
        SaSession session = StpUtil.getSession();
        session.set(SessionAttributeNames.USER_ID, account.userId());
        session.set(SessionAttributeNames.MEMBERSHIP_ID, account.membershipId());
        session.set(SessionAttributeNames.TENANT_ID, account.tenantId());
        session.set(SessionAttributeNames.DEPARTMENT_ID, account.departmentId());
        session.set(SessionAttributeNames.PERMISSION_VERSION, account.permissionVersion());
        session.set(SessionAttributeNames.SESSION_VERSION, account.sessionVersion());
        session.set(SessionAttributeNames.STEP_UP_VERIFIED, false);
        return new AuthenticationService.LoginSession(StpUtil.getTokenValue());
    }

    @Override
    public void logout() {
        StpUtil.logout();
    }
}
