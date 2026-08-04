package com.niv.payment.permission.backoffice;

import com.niv.payment.permission.domain.AccountDomain;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

record BackofficeDeploymentProperties(AccountDomain accountDomain, String loginType,
                                      String allowedOrigin, Set<String> allowedPageComponents) {
    static BackofficeDeploymentProperties of(AccountDomain accountDomain, String loginType,
                                             String origin, String components) {
        if (loginType == null || loginType.isBlank() || origin == null || origin.isBlank()) {
            throw new IllegalArgumentException("Backoffice login type and origin are required");
        }
        if (!accountDomain.loginType().equals(loginType)) {
            throw new IllegalArgumentException("Backoffice login type does not match the fixed account domain");
        }
        Set<String> parsed = new LinkedHashSet<>(Arrays.stream(components.split(","))
            .map(String::trim).filter(value -> !value.isEmpty()).toList());
        return new BackofficeDeploymentProperties(accountDomain, loginType, origin, Set.copyOf(parsed));
    }
}
