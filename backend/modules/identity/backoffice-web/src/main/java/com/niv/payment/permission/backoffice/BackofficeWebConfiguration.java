package com.niv.payment.permission.backoffice;

import cn.dev33.satoken.config.SaTokenConfig;
import cn.dev33.satoken.stp.StpLogic;
import com.niv.payment.permission.cache.RedisLoginAttemptLimiter;
import com.niv.payment.permission.application.CachedPermissionGrantLoader;
import com.niv.payment.permission.application.DefaultAuthorizationService;
import com.niv.payment.permission.application.DefaultScopeMatcher;
import com.niv.payment.permission.cache.JacksonGrantSnapshotCodec;
import com.niv.payment.permission.cache.RedisPermissionGrantCache;
import com.niv.payment.permission.cache.SpringStringRedisValueStore;
import com.niv.payment.permission.domain.AccountDomain;
import com.niv.payment.permission.persistence.repository.JooqCredentialRepository;
import com.niv.payment.permission.persistence.repository.JooqIdentityQueryRepository;
import com.niv.payment.permission.persistence.repository.JooqMembershipSessionVersionRepository;
import com.niv.payment.permission.persistence.repository.JooqMembershipVersionRepository;
import com.niv.payment.permission.persistence.repository.JooqPermissionGrantRepository;
import com.niv.payment.permission.persistence.repository.JooqUserAdministrationRepository;
import com.niv.payment.permission.persistence.repository.JooqRoleAdministrationRepository;
import com.niv.payment.permission.persistence.repository.JooqDepartmentAdministrationRepository;
import com.niv.payment.permission.persistence.repository.JooqMenuAdministrationRepository;
import com.niv.payment.permission.persistence.repository.JooqRoleGrantAdministrationRepository;
import com.niv.payment.permission.persistence.repository.JooqRoleConfigurationRepository;
import com.niv.payment.permission.security.SaTokenSessionBridge;
import com.niv.payment.permission.security.SaTokenSessionIssuer;
import com.niv.payment.permission.security.StpLogicSaTokenFacade;
import com.niv.payment.permission.service.AuthenticationService;
import com.niv.payment.permission.service.IdentityAdministrationService;
import com.niv.payment.permission.service.RoleGrantAdministrationService;
import com.niv.payment.permission.service.RoleConfigurationAdministrationService;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import tools.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import com.niv.payment.dictionary.cache.RedisDictionaryBatchCache;
import com.niv.payment.dictionary.cache.SpringDictionaryRedisStore;
import com.niv.payment.dictionary.core.SystemDictionaryService;
import com.niv.payment.dictionary.persistence.JooqDictionaryCatalogRepository;

import java.time.Duration;

@Import({BackofficeAuthController.class, BackofficeLocalAuthController.class,
    BackofficeUserRoleAdministrationController.class,
    BackofficeRoleGrantAdministrationController.class,
    BackofficeSystemDictionaryReadController.class,
    BackofficeApiExceptionHandler.class,
    BackofficeSecurityConfiguration.class, BackofficeSchemaReadinessConfiguration.class})
public class BackofficeWebConfiguration {
    @Bean
    JooqDictionaryCatalogRepository backofficeDictionaryCatalogRepository(DSLContext dsl) {
        return new JooqDictionaryCatalogRepository(dsl, BackofficeRequestTrace::current);
    }

    @Bean
    SystemDictionaryService backofficeSystemDictionaryService(
        JooqDictionaryCatalogRepository repository, StringRedisTemplate redis, ObjectMapper json,
        @Value("${payment.dictionary.cache-ttl:PT10M}") Duration cacheTtl) {
        return new SystemDictionaryService(repository, new RedisDictionaryBatchCache(
            new SpringDictionaryRedisStore(redis), json, cacheTtl));
    }

    @Bean
    BackofficeAuthenticationModeGuard backofficeAuthenticationModeGuard(
        Environment environment,
        @Value("${payment.identity.local-login-enabled:false}") boolean localLoginEnabled,
        @Value("${payment.oidc.enabled:false}") boolean oidcEnabled) {
        return new BackofficeAuthenticationModeGuard(
            environment.acceptsProfiles(Profiles.of("local", "iam002-local")),
            localLoginEnabled, oidcEnabled);
    }

    @Bean
    BackofficeDeploymentProperties backofficeDeploymentProperties(
        AccountDomain accountDomain,
        @Value("${payment.identity.login-type}") String loginType,
        @Value("${payment.security.allowed-origin}") String origin,
        @Value("${payment.menu.allowed-page-components}") String components) {
        return BackofficeDeploymentProperties.of(accountDomain, loginType, origin, components);
    }

    @Bean
    JooqCredentialRepository backofficeCredentialRepository(DSLContext dsl) {
        return new JooqCredentialRepository(dsl);
    }

    @Bean
    JooqIdentityQueryRepository backofficeIdentityQueryRepository(DSLContext dsl) {
        return new JooqIdentityQueryRepository(dsl);
    }

    @Bean
    JooqRoleGrantAdministrationRepository backofficeRoleGrantAdministrationRepository(
        DSLContext dsl, BackofficeDeploymentProperties properties) {
        return new JooqRoleGrantAdministrationRepository(
            dsl, properties.accountDomain(), BackofficeRequestTrace::current);
    }

    @Bean
    JooqUserAdministrationRepository backofficeUserAdministrationRepository(
        DSLContext dsl, JooqIdentityQueryRepository queries,
        BackofficeDeploymentProperties properties, BCryptPasswordEncoder passwordEncoder,
        Environment environment) {
        String fixtureCredential = environment.getProperty("payment.bootstrap-password");
        boolean localPasswordResetEnabled = environment.getProperty(
            "payment.identity.local-login-enabled", Boolean.class, false);
        return new JooqUserAdministrationRepository(
            dsl, queries, properties.accountDomain(), BackofficeRequestTrace::current,
            () -> fixtureCredential == null || fixtureCredential.isBlank()
                ? null
                : passwordEncoder.encode(fixtureCredential),
            () -> localPasswordResetEnabled, passwordEncoder::encode);
    }

    @Bean
    JooqRoleAdministrationRepository backofficeRoleAdministrationRepository(
        DSLContext dsl, JooqIdentityQueryRepository queries,
        JooqRoleGrantAdministrationRepository grants,
        BackofficeDeploymentProperties properties) {
        return new JooqRoleAdministrationRepository(
            dsl, queries, grants, properties.accountDomain(), BackofficeRequestTrace::current);
    }

    @Bean
    JooqDepartmentAdministrationRepository backofficeDepartmentAdministrationRepository(
        DSLContext dsl, JooqIdentityQueryRepository queries) {
        return new JooqDepartmentAdministrationRepository(
            dsl, queries, BackofficeRequestTrace::current);
    }

    @Bean
    JooqMenuAdministrationRepository backofficeMenuAdministrationRepository(
        DSLContext dsl, JooqIdentityQueryRepository queries) {
        return new JooqMenuAdministrationRepository(dsl, queries, BackofficeRequestTrace::current);
    }

    @Bean
    JooqRoleConfigurationRepository backofficeRoleConfigurationRepository(
        DSLContext dsl, JooqRoleGrantAdministrationRepository grants,
        BackofficeDeploymentProperties properties) {
        return new JooqRoleConfigurationRepository(
            dsl, grants, properties.accountDomain(), BackofficeRequestTrace::current);
    }

    @Bean
    IdentityAdministrationService backofficeIdentityAdministrationService(
        JooqIdentityQueryRepository queries,
        JooqUserAdministrationRepository users,
        JooqRoleAdministrationRepository roles,
        JooqDepartmentAdministrationRepository departments,
        JooqMenuAdministrationRepository menus) {
        return new IdentityAdministrationService(queries, users, roles, departments, menus);
    }

    @Bean
    RoleGrantAdministrationService backofficeRoleGrantAdministrationService(
        JooqRoleGrantAdministrationRepository repository,
        BackofficeDeploymentProperties properties,
        @Value("${payment.permissions.legacy-administration-cutover-complete:false}")
        boolean legacyAdministrationCutoverComplete) {
        return new RoleGrantAdministrationService(
            repository, repository, legacyAdministrationCutoverComplete,
            properties.accountDomain());
    }

    @Bean
    RoleConfigurationAdministrationService backofficeRoleConfigurationAdministrationService(
        JooqRoleConfigurationRepository repository,
        BackofficeDeploymentProperties properties,
        @Value("${payment.permissions.legacy-administration-cutover-complete:false}")
        boolean legacyAdministrationCutoverComplete) {
        return new RoleConfigurationAdministrationService(
            repository, legacyAdministrationCutoverComplete, properties.accountDomain());
    }

    @Bean
    DefaultAuthorizationService backofficeAuthorizationService(
        DSLContext dsl, StringRedisTemplate redis, ObjectMapper json,
        BackofficeDeploymentProperties properties) {
        CachedPermissionGrantLoader loader = new CachedPermissionGrantLoader(
            new JooqMembershipVersionRepository(dsl, properties.accountDomain()),
            new JooqPermissionGrantRepository(dsl, properties.accountDomain()),
            new RedisPermissionGrantCache(
                properties.accountDomain(), new SpringStringRedisValueStore(redis),
                new JacksonGrantSnapshotCodec(json), Duration.ofMinutes(5)));
        return new DefaultAuthorizationService(loader, new DefaultScopeMatcher(
            (ancestorDepartmentId, childDepartmentId) -> false,
            (subject, scope, resource) -> false));
    }

    @Bean
    BackofficeAdministrationPermissionPolicy backofficeAdministrationPermissionPolicy(
        BackofficeDeploymentProperties properties) {
        return new BackofficeAdministrationPermissionPolicy(properties.accountDomain());
    }

    @Bean
    BackofficeAuthorizationEnforcer backofficeAuthorizationEnforcer(
        DefaultAuthorizationService authorization) {
        return new BackofficeAuthorizationEnforcer(authorization);
    }

    @Bean
    BackofficeAccessService backofficeAccessService(JooqIdentityQueryRepository repository) {
        return new BackofficeAccessService(repository);
    }

    @Bean
    BCryptPasswordEncoder backofficePasswordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    StpLogic backofficeStpLogic(SaTokenConfig config, BackofficeDeploymentProperties properties) {
        if (!properties.accountDomain().cookieName().equals(config.getTokenName())) {
            throw new IllegalStateException("Sa-Token Cookie name does not match the fixed account domain");
        }
        return new StpLogic(properties.loginType()).setConfig(config);
    }

    @Bean
    VbenMenuContract backofficeVbenMenuContract(BackofficeDeploymentProperties properties) {
        return new VbenMenuContract(String.join(",", properties.allowedPageComponents()));
    }

    @Bean
    VbenMenuTreeMapper backofficeVbenMenuTreeMapper(ObjectMapper json, VbenMenuContract contract) {
        return new VbenMenuTreeMapper(json, contract);
    }

    @Bean
    AuthenticationService backofficeAuthenticationService(
        BackofficeDeploymentProperties properties, JooqCredentialRepository credentials,
        BCryptPasswordEncoder encoder, StringRedisTemplate redis,
        SaTokenSessionIssuer sessionIssuer) {
        return new AuthenticationService(properties.accountDomain(), credentials, encoder::matches,
            new RedisLoginAttemptLimiter(properties.accountDomain(), redis, 30, 5, Duration.ofMinutes(15)),
            sessionIssuer,
            encoder.encode("dummy-password-not-used"));
    }

    @Bean
    SaTokenSessionIssuer backofficeSessionIssuer(BackofficeDeploymentProperties properties,
                                                 StpLogic stpLogic) {
        return new SaTokenSessionIssuer(stpLogic, properties.accountDomain());
    }

    @Bean
    SaTokenSessionBridge backofficeSessionBridge(BackofficeDeploymentProperties properties,
                                                 DSLContext dsl, StpLogic stpLogic) {
        return new SaTokenSessionBridge(properties.accountDomain(), new StpLogicSaTokenFacade(stpLogic),
            new JooqMembershipSessionVersionRepository(dsl));
    }
}
