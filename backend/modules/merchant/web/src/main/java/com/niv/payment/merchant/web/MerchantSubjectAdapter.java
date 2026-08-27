package com.niv.payment.merchant.web;

import com.niv.payment.merchant.core.AccountDomain;
import com.niv.payment.merchant.core.MerchantActor;
import com.niv.payment.merchant.core.MerchantException;
import com.niv.payment.permission.domain.AuthorizationSubject;
import jakarta.servlet.http.HttpServletRequest;

import java.util.Objects;

final class MerchantSubjectAdapter {
    private final AccountDomain accountDomain;

    MerchantSubjectAdapter(AccountDomain accountDomain) {
        this.accountDomain = Objects.requireNonNull(accountDomain, "accountDomain");
    }

    MerchantActor actor(HttpServletRequest request) {
        Object value = request.getAttribute(AuthorizationSubject.class.getName());
        if (!(value instanceof AuthorizationSubject subject)) {
            throw new MerchantException.PermissionDenied();
        }
        return new MerchantActor(subject.userId(), subject.membershipId(), subject.tenantId(),
            accountDomain, subject.permissionVersion(), subject.sessionVersion(),
            subject.identityVersion(), subject.issuer(), subject.subject(), subject.federated(),
            subject.stepUpVerified());
    }
}
