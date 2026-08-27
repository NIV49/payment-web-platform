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
    OidcFlowService.CodeExchangeClient, OidcStepUpFlowService.AuthorizationClient,
    OidcStepUpFlowService.CodeExchangeClient {
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
        return begin(state, nonce, false);
    }

    @Override
    public OidcFlowService.AuthorizationRequest beginStepUp(String state, String nonce) {
        return begin(state, nonce, true);
    }

    private OidcFlowService.AuthorizationRequest begin(String state, String nonce, boolean stepUp) {
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
                if (stepUp) {
                    parameters.put("prompt", "login");
                    parameters.put("max_age", "0");
                }
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
        return exchange(code, transaction.state(), transaction.codeVerifier(), transaction.nonce());
    }

    @Override
    public OidcFlowService.AuthenticatedIdentity exchange(
        String code, OidcStepUpFlowService.StepUpTransaction transaction) {
        return exchange(code, transaction.state(), transaction.codeVerifier(), transaction.nonce());
    }

    private OidcFlowService.AuthenticatedIdentity exchange(
        String code, String state, String codeVerifier, String nonce) {
        OAuth2AuthorizationRequest request = OAuth2AuthorizationRequest.authorizationCode()
            .authorizationUri(settings.authorizationUri().toString())
            .clientId(settings.clientId())
            .redirectUri(settings.redirectUri().toString())
            .scopes(Set.of("openid"))
            .state(state)
            .attributes(attributes -> {
                attributes.put(OAuth2ParameterNames.REGISTRATION_ID, "keycloak");
                attributes.put(PkceParameterNames.CODE_VERIFIER, codeVerifier);
            })
            .additionalParameters(parameters -> parameters.put(
                OidcParameterNames.NONCE, nonce))
            .build();
        OAuth2AuthorizationResponse response = OAuth2AuthorizationResponse.success(code)
            .redirectUri(settings.redirectUri().toString())
            .state(state)
            .build();
        var tokenResponse = exchangeTokens(new OAuth2AuthorizationCodeGrantRequest(
            registration, new OAuth2AuthorizationExchange(request, response)));
        Object encodedIdToken = tokenResponse.getAdditionalParameters().get(OidcParameterNames.ID_TOKEN);
        if (!(encodedIdToken instanceof String value) || value.isBlank()) {
            throw rejected(OidcFlowService.LoginRejectedException.Reason.ID_TOKEN_MISSING);
        }
        Jwt idToken = decodeIdToken(value);
        validateIdToken(idToken, nonce);
        return new OidcFlowService.AuthenticatedIdentity(
            idToken.getIssuer().toString(), idToken.getSubject(),
            requiredString(idToken, "sid",
                OidcFlowService.LoginRejectedException.Reason.ID_TOKEN_SESSION_ID_INVALID),
            requiredInstant(idToken, IdTokenClaimNames.AUTH_TIME,
                OidcFlowService.LoginRejectedException.Reason.ID_TOKEN_AUTH_TIME_INVALID),
            requiredString(idToken, IdTokenClaimNames.ACR,
                OidcFlowService.LoginRejectedException.Reason.ID_TOKEN_ACR_INVALID), value);
    }

    private org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse exchangeTokens(
        OAuth2AuthorizationCodeGrantRequest request) {
        try {
            return tokens.getTokenResponse(request);
        } catch (RuntimeException exception) {
            throw rejected(OidcFlowService.LoginRejectedException.Reason.TOKEN_EXCHANGE_FAILED,
                exception);
        }
    }

    private Jwt decodeIdToken(String value) {
        try {
            return idTokens.decode(value);
        } catch (RuntimeException exception) {
            throw rejected(OidcFlowService.LoginRejectedException.Reason.ID_TOKEN_DECODE_FAILED,
                exception);
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
                && settings.issuer().toString().equals(token.getIssuer().toString()),
            OidcFlowService.LoginRejectedException.Reason.ID_TOKEN_ISSUER_INVALID);
        require(token.getSubject() != null && !token.getSubject().isBlank(),
            OidcFlowService.LoginRejectedException.Reason.ID_TOKEN_SUBJECT_INVALID);
        require(token.getIssuedAt() != null && token.getExpiresAt() != null,
            OidcFlowService.LoginRejectedException.Reason.ID_TOKEN_TIMESTAMPS_INVALID);
        require(!token.getExpiresAt().isBefore(now.minus(CLOCK_SKEW)),
            OidcFlowService.LoginRejectedException.Reason.ID_TOKEN_EXPIRED);
        require(!token.getIssuedAt().isAfter(now.plus(CLOCK_SKEW)),
            OidcFlowService.LoginRejectedException.Reason.ID_TOKEN_ISSUED_AT_INVALID);
        require(token.getAudience().contains(settings.clientId()),
            OidcFlowService.LoginRejectedException.Reason.ID_TOKEN_AUDIENCE_INVALID);
        require(expectedNonce.equals(token.getClaimAsString(IdTokenClaimNames.NONCE)),
            OidcFlowService.LoginRejectedException.Reason.ID_TOKEN_NONCE_INVALID);
        require(settings.requiredAcr().equals(token.getClaimAsString(IdTokenClaimNames.ACR)),
            OidcFlowService.LoginRejectedException.Reason.ID_TOKEN_ACR_INVALID);
        String authorizedParty = token.getClaimAsString(IdTokenClaimNames.AZP);
        if ((token.getAudience().size() > 1 && authorizedParty == null)
            || (authorizedParty != null && !settings.clientId().equals(authorizedParty))) {
            throw rejected(
                OidcFlowService.LoginRejectedException.Reason.ID_TOKEN_AUTHORIZED_PARTY_INVALID);
        }
        Instant authTime = requiredInstant(token, IdTokenClaimNames.AUTH_TIME,
            OidcFlowService.LoginRejectedException.Reason.ID_TOKEN_AUTH_TIME_INVALID);
        if (authTime.isAfter(now.plus(CLOCK_SKEW))) {
            throw rejected(OidcFlowService.LoginRejectedException.Reason.ID_TOKEN_AUTH_TIME_INVALID);
        }
        requiredString(token, "sid",
            OidcFlowService.LoginRejectedException.Reason.ID_TOKEN_SESSION_ID_INVALID);
    }

    private static void require(boolean accepted,
                                OidcFlowService.LoginRejectedException.Reason reason) {
        if (!accepted) {
            throw rejected(reason);
        }
    }

    private static String requiredString(Jwt token, String claim,
                                         OidcFlowService.LoginRejectedException.Reason reason) {
        String value = token.getClaimAsString(claim);
        if (value == null || value.isBlank()) {
            throw rejected(reason);
        }
        return value;
    }

    private static Instant requiredInstant(Jwt token, String claim,
                                           OidcFlowService.LoginRejectedException.Reason reason) {
        Instant value = token.getClaimAsInstant(claim);
        if (value == null) {
            throw rejected(reason);
        }
        return value;
    }

    private static OidcFlowService.LoginRejectedException rejected(
        OidcFlowService.LoginRejectedException.Reason reason) {
        return new OidcFlowService.LoginRejectedException(reason);
    }

    private static OidcFlowService.LoginRejectedException rejected(
        OidcFlowService.LoginRejectedException.Reason reason, Throwable cause) {
        return new OidcFlowService.LoginRejectedException(reason, cause);
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
