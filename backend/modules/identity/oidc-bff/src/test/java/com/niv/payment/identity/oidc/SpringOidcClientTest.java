package com.niv.payment.identity.oidc;

import com.niv.payment.permission.domain.AccountDomain;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.net.URI;
import java.time.Instant;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpringOidcClientTest {
    private static final Instant NOW = Instant.parse("2026-08-05T04:00:00Z");
    private static final OidcClientSettings SETTINGS = new OidcClientSettings(
        URI.create("https://idp.example.test/realms/PLATFORM"),
        URI.create("https://idp.example.test/realms/PLATFORM/protocol/openid-connect/auth"),
        URI.create("https://idp.example.test/realms/PLATFORM/protocol/openid-connect/token"),
        URI.create("https://idp.example.test/realms/PLATFORM/protocol/openid-connect/certs"),
        URI.create("https://idp.example.test/realms/PLATFORM/protocol/openid-connect/logout"),
        "platform-admin-api", "client-secret",
        URI.create("https://api.ops.example.com/api/auth/oidc/callback"),
        URI.create("https://ops.example.com/login"), "2");

    @Test
    void authorizationRequestContainsPkceNonceAndRequiredAcr() {
        SpringOidcClient client = new SpringOidcClient(SETTINGS, request -> tokenResponse(),
            token -> validJwt("nonce-1", List.of("platform-admin-api"), "2"),
            Clock.fixed(NOW, ZoneOffset.UTC));

        OidcFlowService.AuthorizationRequest request = client.begin("state-1", "nonce-1");

        String query = request.authorizationUri().getRawQuery();
        assertThat(query).contains("response_type=code", "client_id=platform-admin-api",
            "state=state-1", "nonce=nonce-1", "code_challenge=", "code_challenge_method=S256",
            "acr_values=2");
        assertThat(request.codeVerifier()).hasSizeBetween(43, 128);
    }

    @Test
    void stepUpAuthorizationForcesFreshAuthentication() {
        SpringOidcClient client = new SpringOidcClient(SETTINGS, request -> tokenResponse(),
            token -> validJwt("nonce-1", List.of("platform-admin-api"), "2"),
            Clock.fixed(NOW, ZoneOffset.UTC));

        OidcFlowService.AuthorizationRequest request = client.beginStepUp("stepup.state", "nonce-1");

        assertThat(request.authorizationUri().getRawQuery())
            .contains("prompt=login", "max_age=0", "acr_values=2", "code_challenge_method=S256");
    }

    @Test
    void exchangeRequiresExactIssuerAudienceNonceAcrAndSessionClaims() {
        SpringOidcClient valid = client(validJwt("nonce-1", List.of("platform-admin-api"), "2"));

        OidcFlowService.AuthenticatedIdentity identity = valid.exchange("code-1", transaction("nonce-1"));

        assertThat(identity.issuer()).isEqualTo(SETTINGS.issuer().toString());
        assertThat(identity.subject()).isEqualTo("subject-1");
        assertThat(identity.sessionId()).isEqualTo("session-1");
        assertThat(identity.authTime()).isEqualTo(NOW.minusSeconds(30));

        assertRejected(validJwt("wrong-nonce", List.of("platform-admin-api"), "2"));
        assertRejected(validJwt("nonce-1", List.of("other-client"), "2"));
        assertRejected(validJwt("nonce-1", List.of("platform-admin-api"), "1"));
    }

    @Test
    void rejectsProviderEndpointsFromAnotherIssuer() {
        assertThatThrownBy(() -> new OidcClientSettings(
            SETTINGS.issuer(), SETTINGS.authorizationUri(),
            URI.create("https://attacker.example.test/token"), SETTINGS.jwkSetUri(),
            SETTINGS.endSessionUri(), SETTINGS.clientId(), SETTINGS.clientSecret(),
            SETTINGS.redirectUri(), SETTINGS.postLogoutRedirectUri(), SETTINGS.requiredAcr()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsPlainHttpForNonLoopbackOidcEndpoints() {
        assertThatThrownBy(() -> new OidcClientSettings(
            URI.create("http://idp.example.test/realms/PLATFORM"),
            URI.create("http://idp.example.test/realms/PLATFORM/protocol/openid-connect/auth"),
            URI.create("http://idp.example.test/realms/PLATFORM/protocol/openid-connect/token"),
            URI.create("http://idp.example.test/realms/PLATFORM/protocol/openid-connect/certs"),
            URI.create("http://idp.example.test/realms/PLATFORM/protocol/openid-connect/logout"),
            "platform-admin-api", "client-secret",
            URI.create("http://api.ops.example.test/api/auth/oidc/callback"),
            URI.create("http://ops.example.test/login"), "2"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("HTTPS");
    }

    private static void assertRejected(Jwt jwt) {
        SpringOidcClient client = client(jwt);
        assertThatThrownBy(() -> client.exchange("code-1", transaction("nonce-1")))
            .isInstanceOf(OidcFlowService.LoginRejectedException.class);
    }

    private static SpringOidcClient client(Jwt jwt) {
        OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> tokens = request -> tokenResponse();
        JwtDecoder decoder = token -> jwt;
        return new SpringOidcClient(SETTINGS, tokens, decoder, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static OidcFlowService.LoginTransaction transaction(String nonce) {
        return new OidcFlowService.LoginTransaction(
            new OidcFlowService.TrustedEntry("ops.example.com", AccountDomain.PLATFORM, 1L),
            "state-1", "verifier-which-is-long-enough-for-the-test-000000000000", nonce,
            NOW.minusSeconds(10));
    }

    private static OAuth2AccessTokenResponse tokenResponse() {
        return OAuth2AccessTokenResponse.withToken("access-token")
            .tokenType(OAuth2AccessToken.TokenType.BEARER)
            .additionalParameters(Map.of("id_token", "signed-id-token"))
            .build();
    }

    private static Jwt validJwt(String nonce, List<String> audience, String acr) {
        return Jwt.withTokenValue("signed-id-token")
            .header("alg", "RS256")
            .issuer(SETTINGS.issuer().toString())
            .subject("subject-1")
            .audience(audience)
            .issuedAt(NOW.minusSeconds(30))
            .expiresAt(NOW.plusSeconds(300))
            .claim("nonce", nonce)
            .claim("auth_time", NOW.minusSeconds(30))
            .claim("acr", acr)
            .claim("sid", "session-1")
            .build();
    }
}
