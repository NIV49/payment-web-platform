package com.niv.payment.adminapi.config;

import com.niv.payment.adminapi.web.RequestTrace;
import com.niv.payment.merchant.web.MerchantPlatformHttpConfiguration;
import com.niv.payment.merchant.web.MerchantRequestTrace;
import com.niv.payment.merchant.persistence.MerchantAuditTrace;
import com.niv.payment.merchant.persistence.MerchantPlatformPersistenceConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@Import({MerchantPlatformHttpConfiguration.class, MerchantPlatformPersistenceConfiguration.class})
public class MerchantPlatformCompositionConfiguration {
    @Bean
    MerchantRequestTrace merchantRequestTrace() {
        return RequestTrace::current;
    }

    @Bean
    MerchantAuditTrace merchantAuditTrace() {
        return RequestTrace::current;
    }
}
