package com.niv.payment.permission.backoffice;

import cn.dev33.satoken.config.SaTokenConfig;
import cn.dev33.satoken.stp.StpLogic;
import com.niv.payment.permission.cache.RedisLoginAttemptLimiter;
import com.niv.payment.permission.domain.AccountDomain;
import com.niv.payment.permission.persistence.repository.JooqCredentialRepository;
import com.niv.payment.permission.persistence.repository.JooqIdentityQueryRepository;
import com.niv.payment.permission.persistence.repository.JooqMembershipSessionVersionRepository;
import com.niv.payment.permission.security.SaTokenSessionBridge;
import com.niv.payment.permission.security.SaTokenSessionIssuer;
import com.niv.payment.permission.security.StpLogicSaTokenFacade;
import com.niv.payment.permission.service.AuthenticationService;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Duration;

@Import({BackofficeAuthController.class, BackofficeApiExceptionHandler.class,
    BackofficeSecurityConfiguration.class, BackofficeSchemaReadinessConfiguration.class})
public class BackofficeWebConfiguration {
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
        BCryptPasswordEncoder encoder, StringRedisTemplate redis, StpLogic stpLogic) {
        return new AuthenticationService(properties.accountDomain(), credentials, encoder::matches,
            new RedisLoginAttemptLimiter(properties.accountDomain(), redis, 30, 5, Duration.ofMinutes(15)),
            new SaTokenSessionIssuer(stpLogic, properties.accountDomain()),
            encoder.encode("dummy-password-not-used"));
    }

    @Bean
    SaTokenSessionBridge backofficeSessionBridge(BackofficeDeploymentProperties properties,
                                                 DSLContext dsl, StpLogic stpLogic) {
        return new SaTokenSessionBridge(properties.accountDomain(), new StpLogicSaTokenFacade(stpLogic),
            new JooqMembershipSessionVersionRepository(dsl));
    }
}
