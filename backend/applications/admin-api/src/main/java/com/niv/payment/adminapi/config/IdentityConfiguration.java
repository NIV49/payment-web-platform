package com.niv.payment.adminapi.config;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.config.SaCookieConfig;
import cn.dev33.satoken.config.SaTokenConfig;
import com.niv.payment.permission.application.CachedPermissionGrantLoader;
import com.niv.payment.permission.application.DefaultAuthorizationService;
import com.niv.payment.permission.application.DefaultScopeMatcher;
import com.niv.payment.permission.cache.JacksonGrantSnapshotCodec;
import com.niv.payment.permission.cache.RedisPermissionGrantCache;
import com.niv.payment.permission.cache.SpringStringRedisValueStore;
import com.niv.payment.permission.persistence.mapper.PermissionGrantMapper;
import com.niv.payment.permission.persistence.repository.MyBatisMembershipVersionRepository;
import com.niv.payment.permission.persistence.repository.MyBatisPermissionGrantRepository;
import tools.jackson.databind.ObjectMapper;
import com.niv.payment.permission.cache.RedisLoginAttemptLimiter;
import com.niv.payment.adminapi.web.RequestTrace;
import com.niv.payment.permission.persistence.mapper.IdentityAdminMapper;
import com.niv.payment.permission.persistence.mapper.MembershipMapper;
import com.niv.payment.permission.persistence.repository.MyBatisIdentityAdministrationRepository;
import com.niv.payment.permission.persistence.repository.MyBatisMembershipSessionVersionRepository;
import com.niv.payment.permission.security.SaTokenSessionBridge;
import com.niv.payment.permission.security.SaTokenSessionIssuer;
import com.niv.payment.permission.security.StpUtilSaTokenFacade;
import com.niv.payment.permission.service.AuthenticationService;
import com.niv.payment.permission.service.IdentityAdministrationService;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Duration;

@Configuration
@MapperScan("com.niv.payment.permission.persistence.mapper")
public class IdentityConfiguration {
    @Bean
    MyBatisIdentityAdministrationRepository identityRepository(IdentityAdminMapper mapper) {
        return new MyBatisIdentityAdministrationRepository(mapper, RequestTrace::current);
    }

    @Bean
    IdentityAdministrationService identityAdministrationService(MyBatisIdentityAdministrationRepository repository) {
        return new IdentityAdministrationService(repository, repository, repository, repository, repository);
    }
    @Bean
    DefaultAuthorizationService authorizationService(MembershipMapper membershipMapper,
                                                     PermissionGrantMapper grantMapper,
                                                     StringRedisTemplate redis,
                                                     ObjectMapper json) {
        CachedPermissionGrantLoader loader = new CachedPermissionGrantLoader(
            new MyBatisMembershipVersionRepository(membershipMapper),
            new MyBatisPermissionGrantRepository(grantMapper),
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
    AuthenticationService authenticationService(MyBatisIdentityAdministrationRepository repository,
                                                BCryptPasswordEncoder encoder,
                                                StringRedisTemplate redis) {
        return new AuthenticationService(repository, encoder::matches,
            new RedisLoginAttemptLimiter(redis, 5, Duration.ofMinutes(15)),
            new SaTokenSessionIssuer(), encoder.encode("dummy-password-not-used"));
    }

    @Bean
    SaTokenSessionBridge sessionBridge(MembershipMapper mapper) {
        return new SaTokenSessionBridge(new StpUtilSaTokenFacade(),
            new MyBatisMembershipSessionVersionRepository(mapper));
    }

    @Bean
    ApplicationRunner saTokenSecurityInitializer(SaTokenConfig config,
                                                 @Value("${payment.security.cookie-secure}") boolean secure) {
        return ignored -> {
            config.setTokenName("PAYMENT_SESSION")
            .setTimeout(Duration.ofHours(8).toSeconds())
            .setActiveTimeout(Duration.ofMinutes(30).toSeconds())
            .setIsConcurrent(false)
            .setIsShare(false)
            .setIsReadCookie(true)
            .setIsReadHeader(false)
            .setIsReadBody(false)
            .setIsWriteHeader(false)
            .setIsLastingCookie(true)
            .setRightNowCreateTokenSession(true)
            .setCookie(new SaCookieConfig().setHttpOnly(true).setSecure(secure).setSameSite("Strict").setPath("/"));
            SaManager.setConfig(config);
        };
    }

    @Bean
    @Profile("local")
    ApplicationRunner bootstrapPasswordInitializer(IdentityAdminMapper mapper, BCryptPasswordEncoder encoder,
                                                   @Value("${payment.bootstrap-password:${PAYMENT_BOOTSTRAP_PASSWORD:}}")
                                                   String password) {
        return ignored -> {
            if (!password.isBlank()) mapper.initializeBootstrapPassword(encoder.encode(password));
        };
    }
}
