package com.niv.payment.identity.oidc;

import com.niv.payment.permission.domain.AccountDomain;

import java.net.URI;

record KeycloakAdminSettings(URI issuer, URI tokenUri, URI adminBaseUri,
                             String realm, String clientId, String clientSecret) {
    KeycloakAdminSettings {
        if (issuer == null || tokenUri == null || adminBaseUri == null
            || realm == null || clientId == null || clientSecret == null
            || realm.isBlank() || clientId.isBlank() || clientSecret.isBlank()) {
            throw new IllegalArgumentException("Keycloak administration settings are incomplete");
        }
        requireHttpsOrLoopback(issuer);
        requireHttpsOrLoopback(tokenUri);
        requireHttpsOrLoopback(adminBaseUri);
        if (!sameAuthority(issuer, tokenUri) || !sameAuthority(issuer, adminBaseUri)) {
            throw new IllegalArgumentException("Keycloak administration endpoints must share the issuer authority");
        }
    }

    @Override
    public String toString() {
        return "KeycloakAdminSettings[issuer=" + issuer
            + ", tokenUri=" + tokenUri
            + ", adminBaseUri=" + adminBaseUri
            + ", realm=" + realm
            + ", clientId=" + clientId
            + ", clientCredential=[REDACTED]]";
    }

    static KeycloakAdminSettings from(OidcClientSettings oidc, AccountDomain accountDomain,
                                      String adminClientId, KeycloakAdminClientCredential credential) {
        String realm = accountDomain.name();
        if (!oidc.issuer().getPath().endsWith("/realms/" + realm)) {
            throw new IllegalArgumentException("OIDC issuer does not match the fixed Keycloak realm");
        }
        URI root = URI.create(oidc.issuer().getScheme() + "://" + oidc.issuer().getRawAuthority());
        return new KeycloakAdminSettings(oidc.issuer(), oidc.tokenUri(),
            root.resolve("/admin/realms/" + realm), realm, adminClientId, credential.value());
    }

    private static boolean sameAuthority(URI left, URI right) {
        return left.getScheme().equalsIgnoreCase(right.getScheme())
            && left.getRawAuthority().equalsIgnoreCase(right.getRawAuthority());
    }

    private static void requireHttpsOrLoopback(URI value) {
        boolean loopback = "http".equalsIgnoreCase(value.getScheme())
            && ("localhost".equalsIgnoreCase(value.getHost())
                || "127.0.0.1".equals(value.getHost()) || "[::1]".equals(value.getHost()));
        if (!"https".equalsIgnoreCase(value.getScheme()) && !loopback) {
            throw new IllegalArgumentException("Keycloak administration endpoints require HTTPS");
        }
    }
}
