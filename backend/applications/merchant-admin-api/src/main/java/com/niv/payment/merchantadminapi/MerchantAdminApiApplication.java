package com.niv.payment.merchantadminapi;

import com.niv.payment.permission.backoffice.BackofficeWebConfiguration;
import com.niv.payment.permission.domain.AccountDomain;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Bean;

@SpringBootConfiguration
@EnableAutoConfiguration
@Import(BackofficeWebConfiguration.class)
public class MerchantAdminApiApplication {
    @Bean
    AccountDomain merchantAccountDomain() {
        return AccountDomain.MERCHANT;
    }

    public static void main(String[] args) {
        SpringApplication.run(MerchantAdminApiApplication.class, args);
    }
}
