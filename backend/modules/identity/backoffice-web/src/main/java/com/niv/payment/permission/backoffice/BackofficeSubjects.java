package com.niv.payment.permission.backoffice;

import com.niv.payment.permission.domain.AuthorizationSubject;
import jakarta.servlet.http.HttpServletRequest;

final class BackofficeSubjects {
    private BackofficeSubjects() {
    }

    static AuthorizationSubject current(HttpServletRequest request) {
        Object value = request.getAttribute(AuthorizationSubject.class.getName());
        if (value instanceof AuthorizationSubject subject) {
            return subject;
        }
        throw new IllegalStateException("Trusted session is missing");
    }
}
