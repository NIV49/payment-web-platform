package com.niv.payment.agentadminapi;

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
public class AgentAdminApiApplication {
    @Bean
    AccountDomain agentAccountDomain() {
        return AccountDomain.AGENT;
    }

    public static void main(String[] args) {
        SpringApplication.run(AgentAdminApiApplication.class, args);
    }
}
