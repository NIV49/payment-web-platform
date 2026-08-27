package com.niv.payment.identity.oidc;

import com.niv.payment.identity.lifecycle.IdentityGovernanceService;
import com.niv.payment.identity.lifecycle.JooqIdentityInvitationRepository;
import com.niv.payment.permission.domain.AccountDomain;
import org.jooq.DSLContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration(proxyBeanMethods = false)
@Import({IdentityGovernanceQueryController.class, IdentityGovernanceExceptionHandler.class})
public class IdentityGovernanceQueryConfiguration {
    @Bean
    JooqIdentityInvitationRepository identityInvitationRepository(DSLContext dsl,
                                                                  OidcRequestTrace trace) {
        return new JooqIdentityInvitationRepository(dsl, trace::current);
    }

    @Bean
    IdentityGovernanceService identityGovernanceService(
        AccountDomain accountDomain,
        JooqIdentityInvitationRepository repository) {
        return new IdentityGovernanceService(accountDomain, repository);
    }
}
