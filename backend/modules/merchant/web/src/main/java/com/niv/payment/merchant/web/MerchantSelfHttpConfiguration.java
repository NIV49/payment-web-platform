package com.niv.payment.merchant.web;

import com.niv.payment.merchant.core.AccountDomain;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration(proxyBeanMethods = false)
@Import({MerchantSelfController.class, MerchantHttpExceptionHandler.class})
public class MerchantSelfHttpConfiguration {
    @Bean
    MerchantSubjectAdapter merchantSelfSubjectAdapter() {
        return new MerchantSubjectAdapter(AccountDomain.MERCHANT);
    }
}
