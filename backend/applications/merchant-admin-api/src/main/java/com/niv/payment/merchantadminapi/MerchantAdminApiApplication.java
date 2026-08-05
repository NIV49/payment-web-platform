package com.niv.payment.merchantadminapi;

import com.niv.payment.permission.backoffice.BackofficeWebConfiguration;
import com.niv.payment.permission.backoffice.BackofficeRequestTrace;
import com.niv.payment.permission.domain.AccountDomain;
import com.niv.payment.identity.oidc.OidcBffConfiguration;
import com.niv.payment.identity.oidc.OidcClientCredential;
import com.niv.payment.identity.oidc.OidcRequestTrace;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

@SpringBootConfiguration
@EnableAutoConfiguration
@Import({BackofficeWebConfiguration.class, OidcBffConfiguration.class})
public class MerchantAdminApiApplication {
    @Bean
    AccountDomain merchantAccountDomain() {
        return AccountDomain.MERCHANT;
    }

    @Bean
    OidcRequestTrace merchantOidcRequestTrace() {
        return BackofficeRequestTrace::current;
    }

    @Bean
    @ConditionalOnProperty(prefix = "payment.oidc", name = "enabled", havingValue = "true")
    OidcClientCredential merchantOidcClientCredential(Environment environment) {
        return new OidcClientCredential(environment.getRequiredProperty(
            "PAYMENT_MERCHANT_OIDC_CLIENT_" + "SECRET"));
    }

    public static void main(String[] args) {
        SpringApplication.run(MerchantAdminApiApplication.class, args);
    }
}
