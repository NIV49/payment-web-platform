package com.niv.payment.adminapi.config;

import cn.dev33.satoken.config.SaTokenConfig;
import cn.dev33.satoken.stp.StpLogic;
import com.niv.payment.permission.application.CachedPermissionGrantLoader;
import com.niv.payment.permission.application.DefaultAuthorizationService;
import com.niv.payment.permission.application.DefaultScopeMatcher;
import com.niv.payment.permission.domain.AccountDomain;
import com.niv.payment.permission.cache.JacksonGrantSnapshotCodec;
import com.niv.payment.permission.cache.RedisLoginAttemptLimiter;
import com.niv.payment.permission.cache.RedisPermissionGrantCache;
import com.niv.payment.permission.cache.SpringStringRedisValueStore;
import com.niv.payment.permission.backoffice.VbenMenuContract;
import com.niv.payment.permission.backoffice.VbenMenuTreeMapper;
import com.niv.payment.adminapi.web.RequestTrace;
import com.niv.payment.permission.persistence.repository.JooqCredentialRepository;
import com.niv.payment.permission.persistence.repository.JooqDepartmentAdministrationRepository;
import com.niv.payment.permission.persistence.repository.JooqIdentityQueryRepository;
import com.niv.payment.permission.persistence.repository.JooqMembershipSessionVersionRepository;
import com.niv.payment.permission.persistence.repository.JooqMembershipVersionRepository;
import com.niv.payment.permission.persistence.repository.JooqMenuAdministrationRepository;
import com.niv.payment.permission.persistence.repository.JooqPermissionGrantRepository;
import com.niv.payment.permission.persistence.repository.JooqRoleAdministrationRepository;
import com.niv.payment.permission.persistence.repository.JooqRoleGrantAdministrationRepository;
import com.niv.payment.permission.persistence.repository.JooqRoleConfigurationRepository;
import com.niv.payment.permission.persistence.repository.JooqUserAdministrationRepository;
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
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;

@Configuration
public class IdentityConfiguration {
    @Bean
    JooqCredentialRepository credentialRepository(DSLContext dsl) {
        return new JooqCredentialRepository(dsl);
    }

    @Bean
    JooqIdentityQueryRepository identityQueryRepository(DSLContext dsl) {
        return new JooqIdentityQueryRepository(dsl);
    }

    @Bean
    JooqUserAdministrationRepository userAdministrationRepository(
        DSLContext dsl, JooqIdentityQueryRepository queries, BCryptPasswordEncoder passwordEncoder,
        Environment environment) {
        String fixtureCredential = environment.getProperty("payment.bootstrap-password");
        return new JooqUserAdministrationRepository(dsl, queries, RequestTrace::current,
            () -> fixtureCredential == null || fixtureCredential.isBlank()
                ? null
                : passwordEncoder.encode(fixtureCredential));
    }

    @Bean
    JooqRoleAdministrationRepository roleAdministrationRepository(
        DSLContext dsl, JooqIdentityQueryRepository queries,
        JooqRoleGrantAdministrationRepository grants) {
        return new JooqRoleAdministrationRepository(dsl, queries, grants, RequestTrace::current);
    }

    @Bean
    JooqDepartmentAdministrationRepository departmentAdministrationRepository(
        DSLContext dsl, JooqIdentityQueryRepository queries) {
        return new JooqDepartmentAdministrationRepository(dsl, queries, RequestTrace::current);
    }

    @Bean
    JooqMenuAdministrationRepository menuAdministrationRepository(
        DSLContext dsl, JooqIdentityQueryRepository queries) {
        return new JooqMenuAdministrationRepository(dsl, queries, RequestTrace::current);
    }

    @Bean
    JooqRoleGrantAdministrationRepository roleGrantAdministrationRepository(DSLContext dsl) {
        return new JooqRoleGrantAdministrationRepository(dsl, RequestTrace::current);
    }

    @Bean
    JooqRoleConfigurationRepository roleConfigurationRepository(
        DSLContext dsl, JooqRoleGrantAdministrationRepository grants) {
        return new JooqRoleConfigurationRepository(dsl, grants, RequestTrace::current);
    }

    @Bean
    RoleGrantAdministrationService roleGrantAdministrationService(
        JooqRoleGrantAdministrationRepository repository,
        @Value("${payment.permissions.legacy-administration-cutover-complete:false}")
        boolean legacyAdministrationCutoverComplete) {
        return new RoleGrantAdministrationService(
            repository, repository, legacyAdministrationCutoverComplete);
    }

    @Bean
    RoleConfigurationAdministrationService roleConfigurationAdministrationService(
        JooqRoleConfigurationRepository repository,
        @Value("${payment.permissions.legacy-administration-cutover-complete:false}")
        boolean legacyAdministrationCutoverComplete) {
        return new RoleConfigurationAdministrationService(
            repository, legacyAdministrationCutoverComplete);
    }

    @Bean
    IdentityAdministrationService identityAdministrationService(
        JooqIdentityQueryRepository queries,
        JooqUserAdministrationRepository users,
        JooqRoleAdministrationRepository roles,
        JooqDepartmentAdministrationRepository departments,
        JooqMenuAdministrationRepository menus) {
        return new IdentityAdministrationService(queries, users, roles, departments, menus);
    }

    @Bean
    DefaultAuthorizationService authorizationService(DSLContext dsl, StringRedisTemplate redis,
                                                     ObjectMapper json) {
        CachedPermissionGrantLoader loader = new CachedPermissionGrantLoader(
            new JooqMembershipVersionRepository(dsl),
            new JooqPermissionGrantRepository(dsl),
            new RedisPermissionGrantCache(AccountDomain.PLATFORM, new SpringStringRedisValueStore(redis),
                new JacksonGrantSnapshotCodec(json), Duration.ofMinutes(5)));
        return new DefaultAuthorizationService(loader, new DefaultScopeMatcher(
            (ancestorDepartmentId, childDepartmentId) -> false,
            (subject, scope, resource) -> false));
    }


    @Bean
    BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    VbenMenuContract vbenMenuContract(
        @Value("${payment.menu.allowed-page-components:}") String components) {
        return new VbenMenuContract(components);
    }

    @Bean
    VbenMenuTreeMapper vbenMenuTreeMapper(ObjectMapper json, VbenMenuContract contract) {
        return new VbenMenuTreeMapper(json, contract);
    }

    @Bean
    AuthenticationService authenticationService(JooqCredentialRepository repository,
                                                BCryptPasswordEncoder encoder,
                                                StringRedisTemplate redis,
                                                StpLogic stpLogic) {
        return new AuthenticationService(AccountDomain.PLATFORM, repository, encoder::matches,
            new RedisLoginAttemptLimiter(AccountDomain.PLATFORM, redis, 30, 5, Duration.ofMinutes(15)),
            new SaTokenSessionIssuer(stpLogic, AccountDomain.PLATFORM),
            encoder.encode("dummy-password-not-used"));
    }

    @Bean
    StpLogic stpLogic(SaTokenConfig config,
                      @Value("${payment.identity.login-type}") String configuredLoginType) {
        if (!AccountDomain.PLATFORM.loginType().equals(configuredLoginType)
            || !AccountDomain.PLATFORM.cookieName().equals(config.getTokenName())) {
            throw new IllegalStateException("Platform Sa-Token realm does not match the fixed account domain");
        }
        return new StpLogic(AccountDomain.PLATFORM.loginType()).setConfig(config);
    }

    @Bean
    SaTokenSessionBridge sessionBridge(DSLContext dsl, StpLogic stpLogic) {
        return new SaTokenSessionBridge(AccountDomain.PLATFORM, new StpLogicSaTokenFacade(stpLogic),
            new JooqMembershipSessionVersionRepository(dsl));
    }

}
