package com.niv.payment.permission.application;

import com.niv.payment.permission.domain.AuthorizationSubject;
import com.niv.payment.permission.domain.GrantSnapshot;

@FunctionalInterface
public interface PermissionGrantLoader {
    GrantSnapshot load(AuthorizationSubject subject);
}
