package com.niv.payment.identity.oidc;

import cn.dev33.satoken.stp.StpLogic;
import com.niv.payment.identity.lifecycle.JooqMfaRecoveryRepository;
import com.niv.payment.identity.lifecycle.MfaRecoveryRepository;
import com.niv.payment.identity.lifecycle.MfaRecoveryService;
import com.niv.payment.permission.domain.AccountDomain;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "payment.identity.lifecycle", name = "enabled", havingValue = "true")
@EnableScheduling
@Import({MfaRecoveryController.class, MfaRecoveryExceptionHandler.class})
class MfaRecoveryConfiguration {
    @Bean
    MfaRecoveryRepository mfaRecoveryRepository(DSLContext dsl, OidcRequestTrace trace) {
        return new JooqMfaRecoveryRepository(dsl, trace::current);
    }

    @Bean
    MfaRecoveryService mfaRecoveryService(AccountDomain accountDomain,
                                          MfaRecoveryRepository repository) {
        return new MfaRecoveryService(accountDomain, repository);
    }

    @Bean
    KeycloakAdminSettings keycloakAdminSettings(
        OidcClientSettings oidc,
        AccountDomain accountDomain,
        KeycloakAdminClientCredential credential,
        @Value("${payment.identity.lifecycle.admin-client-id}") String adminClientId) {
        return KeycloakAdminSettings.from(oidc, accountDomain, adminClientId, credential);
    }

    @Bean
    KeycloakMfaRecoveryClient keycloakMfaRecoveryClient(KeycloakAdminSettings settings) {
        return new KeycloakMfaRecoveryClient(RestClient.create(), settings);
    }

    @Bean
    SaTokenMfaRecoverySessionRevoker mfaRecoverySessionRevoker(StpLogic stpLogic) {
        return new SaTokenMfaRecoverySessionRevoker(stpLogic);
    }

    @Bean
    MfaRecoveryRelay mfaRecoveryRelay(
        AccountDomain accountDomain,
        MfaRecoveryRepository repository,
        KeycloakMfaRecoveryClient keycloak,
        SaTokenMfaRecoverySessionRevoker sessions,
        @Value("${payment.identity.lifecycle.lease-duration:PT30S}") Duration leaseDuration,
        @Value("${payment.identity.lifecycle.retry-delay:PT15S}") Duration retryDelay) {
        return new MfaRecoveryRelay(accountDomain, repository, keycloak, sessions,
            Clock.systemUTC(), leaseDuration, retryDelay);
    }

    @Bean
    MfaRecoveryRelayScheduler mfaRecoveryRelayScheduler(MfaRecoveryRelay relay) {
        return new MfaRecoveryRelayScheduler(relay);
    }
}
