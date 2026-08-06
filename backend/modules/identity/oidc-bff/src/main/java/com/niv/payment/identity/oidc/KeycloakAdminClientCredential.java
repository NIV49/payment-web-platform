package com.niv.payment.identity.oidc;

public record KeycloakAdminClientCredential(String value) {
    public KeycloakAdminClientCredential {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Keycloak administration client secret is required");
        }
    }

    @Override
    public String toString() {
        return "KeycloakAdminClientCredential[value=[REDACTED]]";
    }
}
