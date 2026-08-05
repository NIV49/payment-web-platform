package com.niv.payment.identity.oidc;

import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationExchange;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationResponse;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.core.endpoint.PkceParameterNames;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.security.oauth2.core.oidc.endpoint.OidcParameterNames;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class SpringOidcClient implements OidcFlowService.AuthorizationClient,
    OidcFlowService.CodeExchangeClient {
    private static final Duration CLOCK_SKEW = Duration.ofSeconds(60);

    private final OidcClientSettings settings;
    private final ClientRegistration registration;
    private final OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> tokens;
    private final JwtDecoder idTokens;
    private final Clock clock;

    public SpringOidcClient(OidcClientSettings settings,
                            OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> tokens,
                            JwtDecoder idTokens) {
        this(settings, tokens, idTokens, Clock.systemUTC());
    }

    SpringOidcClient(OidcClientSettings settings,
                     OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> tokens,
                     JwtDecoder idTokens,
                     Clock clock) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        this.idTokens = Objects.requireNonNull(idTokens, "idTokens");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.registration = clientRegistration(settings);
    }

    @Override
    public OidcFlowService.AuthorizationRequest begin(String state, String nonce) {
        OAuth2AuthorizationRequest.Builder builder = OAuth2AuthorizationRequest.authorizationCode()
            .authorizationUri(settings.authorizationUri().toString())
            .clientId(settings.clientId())
            .redirectUri(settings.redirectUri().toString())
            .scopes(Set.of("openid"))
            .state(state)
            .attributes(attributes -> attributes.put(OAuth2ParameterNames.REGISTRATION_ID, "keycloak"))
            .additionalParameters(parameters -> {
                parameters.put(OidcParameterNames.NONCE, nonce);
                parameters.put("acr_values", settings.requiredAcr());
                parameters.put("claims", "{\"id_token\":{\"acr\":{\"essential\":true,\"values\":[\""
                    + settings.requiredAcr() + "\"]},\"auth_time\":{\"essential\":true}}}");
            });
        OAuth2AuthorizationRequestCustomizers.withPkce().accept(builder);
        OAuth2AuthorizationRequest request = builder.build();
        String verifier = request.getAttribute(PkceParameterNames.CODE_VERIFIER);
        return new OidcFlowService.AuthorizationRequest(
            URI.create(request.getAuthorizationRequestUri()), verifier);
    }

    @Override
    public OidcFlowService.AuthenticatedIdentity exchange(
        String code, OidcFlowService.LoginTransaction transaction) {
        try {
            OAuth2AuthorizationRequest request = OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri(settings.authorizationUri().toString())
                .clientId(settings.clientId())
                .redirectUri(settings.redirectUri().toString())
                .scopes(Set.of("openid"))
                .state(transaction.state())
                .attributes(attributes -> {
                    attributes.put(OAuth2ParameterNames.REGISTRATION_ID, "keycloak");
                    attributes.put(PkceParameterNames.CODE_VERIFIER, transaction.codeVerifier());
                })
                .additionalParameters(parameters -> parameters.put(
                    OidcParameterNames.NONCE, transaction.nonce()))
                .build();
            OAuth2AuthorizationResponse response = OAuth2AuthorizationResponse.success(code)
                .redirectUri(settings.redirectUri().toString())
                .state(transaction.state())
                .build();
            var tokenResponse = tokens.getTokenResponse(new OAuth2AuthorizationCodeGrantRequest(
                registration, new OAuth2AuthorizationExchange(request, response)));
            Object encodedIdToken = tokenResponse.getAdditionalParameters().get(OidcParameterNames.ID_TOKEN);
            if (!(encodedIdToken instanceof String value) || value.isBlank()) {
                throw new OidcFlowService.LoginRejectedException();
            }
            Jwt idToken = idTokens.decode(value);
            validateIdToken(idToken, transaction.nonce());
            return new OidcFlowService.AuthenticatedIdentity(
                idToken.getIssuer().toString(), idToken.getSubject(), requiredString(idToken, "sid"),
                requiredInstant(idToken, IdTokenClaimNames.AUTH_TIME),
                requiredString(idToken, IdTokenClaimNames.ACR), value);
        } catch (OidcFlowService.LoginRejectedException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new OidcFlowService.LoginRejectedException(exception);
        }
    }

    public URI logoutUri(String idToken) {
        String encodedToken = java.net.URLEncoder.encode(idToken, java.nio.charset.StandardCharsets.UTF_8);
        String encodedRedirect = java.net.URLEncoder.encode(
            settings.postLogoutRedirectUri().toString(), java.nio.charset.StandardCharsets.UTF_8);
        return URI.create(settings.endSessionUri() + "?id_token_hint=" + encodedToken
            + "&post_logout_redirect_uri=" + encodedRedirect + "&client_id="
            + java.net.URLEncoder.encode(settings.clientId(), java.nio.charset.StandardCharsets.UTF_8));
    }

    private void validateIdToken(Jwt token, String expectedNonce) {
        Instant now = clock.instant();
        require(token.getIssuer() != null
            && settings.issuer().toString().equals(token.getIssuer().toString()), "issuer");
        require(token.getSubject() != null && !token.getSubject().isBlank(), "subject");
        require(token.getIssuedAt() != null && token.getExpiresAt() != null, "timestamps");
        require(!token.getExpiresAt().isBefore(now.minus(CLOCK_SKEW)), "expired");
        require(!token.getIssuedAt().isAfter(now.plus(CLOCK_SKEW)), "issued-at");
        require(token.getAudience().contains(settings.clientId()), "audience");
        require(expectedNonce.equals(token.getClaimAsString(IdTokenClaimNames.NONCE)), "nonce");
        require(settings.requiredAcr().equals(token.getClaimAsString(IdTokenClaimNames.ACR)), "acr");
        String authorizedParty = token.getClaimAsString(IdTokenClaimNames.AZP);
        if ((token.getAudience().size() > 1 && authorizedParty == null)
            || (authorizedParty != null && !settings.clientId().equals(authorizedParty))) {
            throw new OidcFlowService.LoginRejectedException();
        }
        Instant authTime = requiredInstant(token, IdTokenClaimNames.AUTH_TIME);
        if (authTime.isAfter(now.plus(CLOCK_SKEW))) {
            throw new OidcFlowService.LoginRejectedException();
        }
        requiredString(token, "sid");
    }

    private static void require(boolean accepted, String claim) {
        if (!accepted) {
            throw new OidcFlowService.LoginRejectedException(
                new IllegalArgumentException("Invalid ID token " + claim));
        }
    }

    private static String requiredString(Jwt token, String claim) {
        String value = token.getClaimAsString(claim);
        if (value == null || value.isBlank()) {
            throw new OidcFlowService.LoginRejectedException();
        }
        return value;
    }

    private static Instant requiredInstant(Jwt token, String claim) {
        Instant value = token.getClaimAsInstant(claim);
        if (value == null) {
            throw new OidcFlowService.LoginRejectedException();
        }
        return value;
    }

    private static ClientRegistration clientRegistration(OidcClientSettings settings) {
        return ClientRegistration.withRegistrationId("keycloak")
            .clientId(settings.clientId())
            .clientSecret(settings.clientSecret())
            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri(settings.redirectUri().toString())
            .scope(List.of("openid"))
            .authorizationUri(settings.authorizationUri().toString())
            .tokenUri(settings.tokenUri().toString())
            .jwkSetUri(settings.jwkSetUri().toString())
            .issuerUri(settings.issuer().toString())
            .userNameAttributeName(IdTokenClaimNames.SUB)
            .clientName("keycloak")
            .clientSettings(ClientRegistration.ClientSettings.builder().requireProofKey(true).build())
            .build();
    }
}
