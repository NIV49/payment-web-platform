package com.niv.payment.identity.oidc;

import cn.dev33.satoken.stp.StpLogic;
import com.niv.payment.identity.lifecycle.JooqMfaRecoveryRepository;
import com.niv.payment.identity.lifecycle.JooqIdentityInvitationRepository;
import com.niv.payment.identity.lifecycle.IdentityGovernanceService;
import com.niv.payment.identity.lifecycle.MemberInvitationService;
import com.niv.payment.identity.lifecycle.MfaRecoveryRepository;
import com.niv.payment.identity.lifecycle.MfaRecoveryService;
import com.niv.payment.identity.lifecycle.TenantBootstrapService;
import com.niv.payment.permission.domain.AccountDomain;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "payment.identity.lifecycle", name = "enabled", havingValue = "true")
@EnableScheduling
@Import({MfaRecoveryController.class, MfaRecoveryExceptionHandler.class,
    IdentityGovernanceController.class, IdentityGovernanceExceptionHandler.class,
    TenantBootstrapController.class})
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
    KeycloakAdminRealmRegistry keycloakAdminRealmRegistry(
        OidcClientSettings oidc,
        AccountDomain accountDomain,
        KeycloakAdminClientCredential credential,
        Environment environment,
        @Value("${payment.identity.lifecycle.admin-client-id}") String adminClientId,
        @Value("${payment.identity.lifecycle.tenant-bootstrap-enabled:false}")
        boolean tenantBootstrapEnabled) {
        KeycloakAdminRealmRegistry.Builder builder = KeycloakAdminRealmRegistry.builder(oidc)
            .add(accountDomain, adminClientId, credential);
        if (tenantBootstrapEnabled) {
            if (accountDomain != AccountDomain.PLATFORM) {
                throw new IllegalStateException("Tenant bootstrap is only valid in the PLATFORM API");
            }
            builder.add(AccountDomain.MERCHANT,
                    environment.getRequiredProperty(
                        "payment.identity.lifecycle.merchant-admin-client-id"),
                    new KeycloakAdminClientCredential(environment.getRequiredProperty(
                        "PAYMENT_MERCHANT_KEYCLOAK_ADMIN_CLIENT_SECRET")))
                .add(AccountDomain.AGENT,
                    environment.getRequiredProperty(
                        "payment.identity.lifecycle.agent-admin-client-id"),
                    new KeycloakAdminClientCredential(environment.getRequiredProperty(
                        "PAYMENT_AGENT_KEYCLOAK_ADMIN_CLIENT_SECRET")));
        }
        return builder.build();
    }

    @Bean
    KeycloakAdminSettings keycloakAdminSettings(KeycloakAdminRealmRegistry realms,
                                                AccountDomain accountDomain) {
        return realms.require(accountDomain);
    }

    @Bean
    KeycloakMfaRecoveryClient keycloakMfaRecoveryClient(KeycloakAdminSettings settings) {
        return new KeycloakMfaRecoveryClient(RestClient.create(), settings);
    }

    @Bean
    JooqIdentityInvitationRepository identityInvitationRepository(DSLContext dsl,
                                                                  OidcRequestTrace trace) {
        return new JooqIdentityInvitationRepository(dsl, trace::current);
    }

    @Bean
    KeycloakIdentityProvisioningClient keycloakIdentityProvisioningClient(
        KeycloakAdminRealmRegistry realms,
        @Value("${payment.identity.lifecycle.invitation-action-lifespan:PT24H}")
        Duration actionLifespan) {
        return new KeycloakIdentityProvisioningClient(RestClient.create(), realms, actionLifespan);
    }

    @Bean
    MemberInvitationService memberInvitationService(AccountDomain accountDomain,
                                                     JooqIdentityInvitationRepository repository,
                                                     KeycloakIdentityProvisioningClient provisioner) {
        return new MemberInvitationService(accountDomain, repository, provisioner);
    }

    @Bean
    IdentityGovernanceService identityGovernanceService(AccountDomain accountDomain,
                                                        JooqIdentityInvitationRepository repository) {
        return new IdentityGovernanceService(accountDomain, repository);
    }

    @Bean
    @ConditionalOnProperty(prefix = "payment.identity.lifecycle", name = "tenant-bootstrap-enabled",
        havingValue = "true")
    TenantBootstrapService tenantBootstrapService(AccountDomain accountDomain,
                                                  JooqIdentityInvitationRepository repository,
                                                  KeycloakIdentityProvisioningClient provisioner) {
        if (accountDomain != AccountDomain.PLATFORM) {
            throw new IllegalStateException("Tenant bootstrap is only valid in the PLATFORM API");
        }
        return new TenantBootstrapService(repository, provisioner);
    }

    @Bean
    IdentityInvitationRelay identityInvitationRelay(
        AccountDomain accountDomain,
        JooqIdentityInvitationRepository repository,
        KeycloakIdentityProvisioningClient keycloak,
        @Value("${payment.identity.lifecycle.lease-duration:PT30S}") Duration leaseDuration,
        @Value("${payment.identity.lifecycle.retry-delay:PT15S}") Duration retryDelay) {
        return new IdentityInvitationRelay(accountDomain, repository, keycloak,
            Clock.systemUTC(), leaseDuration, retryDelay);
    }

    @Bean
    IdentityInvitationRelayScheduler identityInvitationRelayScheduler(
        IdentityInvitationRelay relay) {
        return new IdentityInvitationRelayScheduler(relay);
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
