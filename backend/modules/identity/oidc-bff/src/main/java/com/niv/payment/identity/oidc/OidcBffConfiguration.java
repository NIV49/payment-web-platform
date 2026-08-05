package com.niv.payment.identity.oidc;

import cn.dev33.satoken.stp.StpLogic;
import com.niv.payment.permission.domain.AccountDomain;
import com.niv.payment.permission.security.SaTokenSessionIssuer;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "payment.oidc", name = "enabled", havingValue = "true")
@Import({OidcBffController.class, OidcBffExceptionHandler.class})
public class OidcBffConfiguration {
    @Bean
    OidcClientSettings oidcClientSettings(
        @Value("${payment.oidc.issuer}") URI issuer,
        @Value("${payment.oidc.authorization-uri}") URI authorizationUri,
        @Value("${payment.oidc.token-uri}") URI tokenUri,
        @Value("${payment.oidc.jwk-set-uri}") URI jwkSetUri,
        @Value("${payment.oidc.end-session-uri}") URI endSessionUri,
        @Value("${payment.oidc.client-id}") String clientId,
        OidcClientCredential clientCredential,
        @Value("${payment.oidc.redirect-uri}") URI redirectUri,
        @Value("${payment.oidc.post-logout-redirect-uri}") URI postLogoutRedirectUri,
        @Value("${payment.oidc.required-acr:2}") String requiredAcr) {
        return new OidcClientSettings(issuer, authorizationUri, tokenUri, jwkSetUri, endSessionUri,
            clientId, clientCredential.value(), redirectUri, postLogoutRedirectUri, requiredAcr);
    }

    @Bean
    JwtDecoder oidcJwtDecoder(OidcClientSettings settings) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(settings.jwkSetUri().toString())
            .jwsAlgorithm(SignatureAlgorithm.RS256)
            .build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(settings.issuer().toString()));
        return decoder;
    }

    @Bean
    SpringOidcClient springOidcClient(OidcClientSettings settings, JwtDecoder oidcJwtDecoder) {
        return new SpringOidcClient(settings, new RestClientAuthorizationCodeTokenResponseClient(), oidcJwtDecoder);
    }

    @Bean
    RedisOidcStateStore oidcStateStore(
        StringRedisTemplate redis,
        ObjectMapper json,
        AccountDomain accountDomain,
        @Value("${payment.oidc.transaction-ttl:PT5M}") Duration transactionTtl,
        @Value("${payment.oidc.handoff-ttl:PT1M}") Duration handoffTtl) {
        return new RedisOidcStateStore(redis, json, accountDomain.cacheNamespace(), transactionTtl, handoffTtl);
    }

    @Bean
    JooqTrustedEntryResolver trustedEntryResolver(DSLContext dsl) {
        return new JooqTrustedEntryResolver(dsl);
    }

    @Bean
    JooqOidcIdentityRepository oidcIdentityRepository(DSLContext dsl) {
        return new JooqOidcIdentityRepository(dsl);
    }

    @Bean
    OidcSessionAuthenticator oidcSessionAuthenticator(
        AccountDomain accountDomain,
        JooqOidcIdentityRepository identities,
        SaTokenSessionIssuer sessions,
        OidcSessionIndex sessionIndex) {
        return new OidcSessionAuthenticator(accountDomain, identities, sessions, sessionIndex);
    }

    @Bean
    OidcFlowService oidcFlowService(
        AccountDomain accountDomain,
        JooqTrustedEntryResolver entries,
        SpringOidcClient client,
        RedisOidcStateStore state,
        OidcSessionAuthenticator sessions,
        @Value("${payment.oidc.public-scheme:https}") String publicScheme,
        @Value("${payment.oidc.frontend-callback-path:/auth/oidc/callback}") String callbackPath) {
        return new OidcFlowService(accountDomain, entries, client, client, state, state, sessions,
            Clock.systemUTC(), new SecureOpaqueValueGenerator(new SecureRandom()), publicScheme, callbackPath);
    }

    @Bean
    OidcStepUpSession oidcStepUpSession(AccountDomain accountDomain, StpLogic stpLogic) {
        return new OidcStepUpSession(accountDomain, stpLogic);
    }

    @Bean
    OidcStepUpFlowService oidcStepUpFlowService(
        AccountDomain accountDomain,
        JooqTrustedEntryResolver entries,
        SpringOidcClient client,
        RedisOidcStateStore state,
        OidcStepUpSession sessions,
        @Value("${payment.oidc.public-scheme:https}") String publicScheme,
        @Value("${payment.oidc.frontend-callback-path:/auth/oidc/callback}") String callbackPath) {
        return new OidcStepUpFlowService(accountDomain, entries, client, client, state, state,
            sessions, Clock.systemUTC(), new SecureOpaqueValueGenerator(new SecureRandom()),
            publicScheme, callbackPath);
    }

    @Bean
    OidcSessionLogoutService oidcSessionLogoutService(
        StpLogic stpLogic, SpringOidcClient client, OidcClientSettings settings) {
        return new OidcSessionLogoutService(stpLogic, client, settings);
    }

    @Bean
    OidcSessionIndex oidcSessionIndex(
        StringRedisTemplate redis,
        AccountDomain accountDomain,
        @Value("${payment.oidc.application-session-index-ttl:PT9H}") Duration sessionTtl,
        @Value("${payment.oidc.logout-processing-ttl:PT30S}") Duration processingTtl,
        @Value("${payment.oidc.logout-replay-ttl:PT24H}") Duration completedTtl) {
        return new RedisOidcSessionIndex(redis, accountDomain.cacheNamespace(), sessionTtl,
            processingTtl, completedTtl);
    }

    @Bean
    OidcLogoutTokenVerifier oidcLogoutTokenVerifier(
        OidcClientSettings settings,
        JwtDecoder oidcJwtDecoder,
        @Value("${payment.oidc.logout-event-maximum-age:PT5M}") Duration maximumAge) {
        return new OidcLogoutTokenVerifier(settings, oidcJwtDecoder, Clock.systemUTC(), maximumAge);
    }

    @Bean
    OidcBackChannelLogoutService oidcBackChannelLogoutService(
        OidcLogoutTokenVerifier verifier, OidcSessionIndex index, StpLogic stpLogic) {
        return new OidcBackChannelLogoutService(verifier, index, stpLogic::logout);
    }
}
