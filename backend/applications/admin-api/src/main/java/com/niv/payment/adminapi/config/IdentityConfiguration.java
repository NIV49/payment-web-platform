package com.niv.payment.adminapi.config;

import com.niv.payment.permission.application.CachedPermissionGrantLoader;
import com.niv.payment.permission.application.DefaultAuthorizationService;
import com.niv.payment.permission.application.DefaultScopeMatcher;
import com.niv.payment.permission.cache.JacksonGrantSnapshotCodec;
import com.niv.payment.permission.cache.RedisLoginAttemptLimiter;
import com.niv.payment.permission.cache.RedisPermissionGrantCache;
import com.niv.payment.permission.cache.SpringStringRedisValueStore;
import com.niv.payment.adminapi.web.RequestTrace;
import com.niv.payment.permission.persistence.repository.JooqCredentialRepository;
import com.niv.payment.permission.persistence.repository.JooqDepartmentAdministrationRepository;
import com.niv.payment.permission.persistence.repository.JooqIdentityQueryRepository;
import com.niv.payment.permission.persistence.repository.JooqMembershipSessionVersionRepository;
import com.niv.payment.permission.persistence.repository.JooqMembershipVersionRepository;
import com.niv.payment.permission.persistence.repository.JooqMenuAdministrationRepository;
import com.niv.payment.permission.persistence.repository.JooqPermissionGrantRepository;
import com.niv.payment.permission.persistence.repository.JooqRoleAdministrationRepository;
import com.niv.payment.permission.persistence.repository.JooqUserAdministrationRepository;
import com.niv.payment.permission.security.SaTokenSessionBridge;
import com.niv.payment.permission.security.SaTokenSessionIssuer;
import com.niv.payment.permission.security.StpUtilSaTokenFacade;
import com.niv.payment.permission.service.AuthenticationService;
import com.niv.payment.permission.service.IdentityAdministrationService;
import org.jooq.DSLContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
        DSLContext dsl, JooqIdentityQueryRepository queries) {
        return new JooqUserAdministrationRepository(dsl, queries, RequestTrace::current);
    }

    @Bean
    JooqRoleAdministrationRepository roleAdministrationRepository(
        DSLContext dsl, JooqIdentityQueryRepository queries) {
        return new JooqRoleAdministrationRepository(dsl, queries, RequestTrace::current);
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
            new RedisPermissionGrantCache(new SpringStringRedisValueStore(redis),
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
    AuthenticationService authenticationService(JooqCredentialRepository repository,
                                                BCryptPasswordEncoder encoder,
                                                StringRedisTemplate redis) {
        return new AuthenticationService(repository, encoder::matches,
            new RedisLoginAttemptLimiter(redis, 30, 5, Duration.ofMinutes(15)),
            new SaTokenSessionIssuer(), encoder.encode("dummy-password-not-used"));
    }

    @Bean
    SaTokenSessionBridge sessionBridge(DSLContext dsl) {
        return new SaTokenSessionBridge(new StpUtilSaTokenFacade(),
            new JooqMembershipSessionVersionRepository(dsl));
    }

}
