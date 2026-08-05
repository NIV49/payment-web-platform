package com.niv.payment.identity.oidc;

public record OidcClientCredential(String value) {
    public OidcClientCredential {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("OIDC confidential client value is required");
        }
    }

    @Override
    public String toString() {
        return "OidcClientCredential[value=[REDACTED]]";
    }
}
