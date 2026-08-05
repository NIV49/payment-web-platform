package com.niv.payment.identity.oidc;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;

public record OidcClientSettings(URI issuer,
                                 URI authorizationUri,
                                 URI tokenUri,
                                 URI jwkSetUri,
                                 URI endSessionUri,
                                 String clientId,
                                 String clientSecret,
                                 URI redirectUri,
                                 URI postLogoutRedirectUri,
                                 String requiredAcr) {
    public OidcClientSettings {
        issuer = requireAbsoluteHttpUri(issuer, "issuer");
        authorizationUri = requireAbsoluteHttpUri(authorizationUri, "authorizationUri");
        tokenUri = requireAbsoluteHttpUri(tokenUri, "tokenUri");
        jwkSetUri = requireAbsoluteHttpUri(jwkSetUri, "jwkSetUri");
        endSessionUri = requireAbsoluteHttpUri(endSessionUri, "endSessionUri");
        redirectUri = requireAbsoluteHttpUri(redirectUri, "redirectUri");
        postLogoutRedirectUri = requireAbsoluteHttpUri(postLogoutRedirectUri, "postLogoutRedirectUri");
        requireTlsOrLoopback(issuer, "issuer");
        requireTlsOrLoopback(authorizationUri, "authorizationUri");
        requireTlsOrLoopback(tokenUri, "tokenUri");
        requireTlsOrLoopback(jwkSetUri, "jwkSetUri");
        requireTlsOrLoopback(endSessionUri, "endSessionUri");
        requireTlsOrLoopback(redirectUri, "redirectUri");
        requireTlsOrLoopback(postLogoutRedirectUri, "postLogoutRedirectUri");
        clientId = requireText(clientId, "clientId");
        requireText(clientSecret, "clientSecret");
        requiredAcr = requireText(requiredAcr, "requiredAcr");
        if (issuer.getQuery() != null || issuer.getFragment() != null) {
            throw new IllegalArgumentException("OIDC issuer must not contain a query or fragment");
        }
        requireProviderEndpoint(issuer, authorizationUri, "authorizationUri");
        requireProviderEndpoint(issuer, tokenUri, "tokenUri");
        requireProviderEndpoint(issuer, jwkSetUri, "jwkSetUri");
        requireProviderEndpoint(issuer, endSessionUri, "endSessionUri");
        if (!requiredAcr.matches("[A-Za-z0-9._:-]{1,64}")) {
            throw new IllegalArgumentException("requiredAcr is invalid");
        }
    }

    private static URI requireAbsoluteHttpUri(URI value, String name) {
        Objects.requireNonNull(value, name);
        if (!value.isAbsolute() || value.getHost() == null
            || !("https".equals(value.getScheme()) || "http".equals(value.getScheme()))) {
            throw new IllegalArgumentException(name + " must be an absolute HTTP URI");
        }
        return value;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    private static void requireTlsOrLoopback(URI value, String name) {
        if ("https".equals(value.getScheme())) {
            return;
        }
        String host = value.getHost().toLowerCase(Locale.ROOT);
        if (!("localhost".equals(host) || "::1".equals(host) || "[::1]".equals(host)
            || host.startsWith("127."))) {
            throw new IllegalArgumentException(name + " must use HTTPS outside loopback development");
        }
    }

    private static void requireProviderEndpoint(URI issuer, URI endpoint, String name) {
        String issuerPath = issuer.getPath() == null ? "" : issuer.getPath();
        String endpointPath = endpoint.getPath() == null ? "" : endpoint.getPath();
        if (!issuer.getScheme().equals(endpoint.getScheme())
            || !issuer.getAuthority().equals(endpoint.getAuthority())
            || !endpointPath.startsWith(issuerPath + "/protocol/openid-connect/")) {
            throw new IllegalArgumentException(name + " must belong to the configured Keycloak issuer");
        }
    }
}
