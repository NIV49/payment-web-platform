package com.niv.payment.identity.oidc;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtException;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OidcLogoutJwtDecoderTest {
    @Test
    void usesATypeAwareDecoderForBackChannelLogoutTokens() throws Exception {
        RSAKey key = new RSAKeyGenerator(2048).keyID("logout-key").generate();
        HttpServer jwks = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] jwkSet = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
        jwks.createContext("/realms/PLATFORM/protocol/openid-connect/certs", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, jwkSet.length);
            exchange.getResponseBody().write(jwkSet);
            exchange.close();
        });
        jwks.start();
        try {
            URI issuer = URI.create(
                "http://127.0.0.1:" + jwks.getAddress().getPort() + "/realms/PLATFORM");
            URI jwkSetUri = URI.create(issuer + "/protocol/openid-connect/certs");
            OidcClientSettings settings = settings(issuer, jwkSetUri);
            String token = logoutToken(key, issuer);
            OidcBffConfiguration configuration = new OidcBffConfiguration();

            assertThatThrownBy(() -> configuration.oidcJwtDecoder(settings).decode(token))
                .isInstanceOf(JwtException.class);
            var logoutDecoder = configuration.oidcLogoutJwtDecoder(settings);
            assertThat(logoutDecoder.decode(token).getSubject())
                .isEqualTo("subject-1");
            assertThat(new OidcLogoutTokenVerifier(
                settings, logoutDecoder, Clock.systemUTC(), Duration.ofMinutes(5))
                .verify(token).sessionId()).isEqualTo("session-1");
        } finally {
            jwks.stop(0);
        }
    }

    private static OidcClientSettings settings(URI issuer, URI jwkSetUri) {
        return new OidcClientSettings(
            issuer,
            URI.create(issuer + "/protocol/openid-connect/auth"),
            URI.create(issuer + "/protocol/openid-connect/token"),
            jwkSetUri,
            URI.create(issuer + "/protocol/openid-connect/logout"),
            "platform-admin-api", "client-secret",
            URI.create("https://api.ops.example.test/api/auth/oidc/callback"),
            URI.create("https://ops.example.test/login"), "2");
    }

    private static String logoutToken(RSAKey key, URI issuer) throws Exception {
        Instant now = Instant.now();
        SignedJWT token = new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256)
                .type(new JOSEObjectType("logout+jwt"))
                .keyID(key.getKeyID())
                .build(),
            new JWTClaimsSet.Builder()
                .issuer(issuer.toString())
                .subject("subject-1")
                .audience(List.of("platform-admin-api"))
                .issueTime(Date.from(now.minusSeconds(5)))
                .expirationTime(Date.from(now.plusSeconds(60)))
                .jwtID("event-1")
                .claim("sid", "session-1")
                .claim("events", Map.of(OidcLogoutTokenVerifier.BACKCHANNEL_EVENT, Map.of()))
                .build());
        token.sign(new RSASSASigner(key));
        return token.serialize();
    }
}
