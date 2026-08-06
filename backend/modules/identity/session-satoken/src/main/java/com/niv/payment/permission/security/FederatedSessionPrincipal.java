package com.niv.payment.permission.security;

import com.niv.payment.permission.domain.AccountDomain;

import java.time.Instant;
import java.util.Objects;

public record FederatedSessionPrincipal(long userId,
                                        long membershipId,
                                        long tenantId,
                                        Long departmentId,
                                        long permissionVersion,
                                        long sessionVersion,
                                        long identityVersion,
                                        AccountDomain accountDomain,
                                        String entryHost,
                                        String issuer,
                                        String subject,
                                        String oidcSessionId,
                                        Instant authTime,
                                        String acr,
                                        String idToken) {
    public FederatedSessionPrincipal {
        if (userId <= 0 || membershipId <= 0 || tenantId <= 0 || permissionVersion < 0
            || sessionVersion < 0 || identityVersion < 0) {
            throw new IllegalArgumentException("Federated session identity is invalid");
        }
        Objects.requireNonNull(accountDomain, "accountDomain");
        requireText(entryHost, "entryHost");
        requireText(issuer, "issuer");
        requireText(subject, "subject");
        requireText(oidcSessionId, "oidcSessionId");
        Objects.requireNonNull(authTime, "authTime");
        requireText(acr, "acr");
        requireText(idToken, "idToken");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }
}
