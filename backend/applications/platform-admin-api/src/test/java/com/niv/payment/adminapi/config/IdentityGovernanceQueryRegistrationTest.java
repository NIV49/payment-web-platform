package com.niv.payment.adminapi.config;

import com.niv.payment.identity.lifecycle.IdentityGovernanceService;
import com.niv.payment.identity.oidc.IdentityGovernanceQueryConfiguration;
import com.niv.payment.identity.oidc.OidcRequestTrace;
import com.niv.payment.permission.domain.AccountDomain;
import com.niv.payment.permission.security.SaTokenSessionBridge;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class IdentityGovernanceQueryRegistrationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
        .withUserConfiguration(IdentityGovernanceQueryConfiguration.class)
        .withBean(AccountDomain.class, () -> AccountDomain.PLATFORM)
        .withBean(DSLContext.class, () -> mock(DSLContext.class))
        .withBean(SaTokenSessionBridge.class, () -> mock(SaTokenSessionBridge.class))
        .withBean(OidcRequestTrace.class, () -> () -> "trace-local");

    @Test
    void memberQueriesAreRegisteredWithoutOidcLifecycleWrites() {
        context.run(result -> {
            assertThat(result).hasNotFailed();
            assertThat(result).hasSingleBean(IdentityGovernanceService.class);
            assertThat(result.getBeanNamesForAnnotation(RestController.class))
                .contains("com.niv.payment.identity.oidc.IdentityGovernanceQueryController")
                .doesNotContain("com.niv.payment.identity.oidc.IdentityGovernanceController");
        });
    }

    @Test
    void platformCompositionRootImportsTheQueryConfigurationDirectly() {
        Import imported = IdentityConfiguration.class.getAnnotation(Import.class);

        assertThat(imported).isNotNull();
        assertThat(imported.value()).contains(IdentityGovernanceQueryConfiguration.class);
    }
}
