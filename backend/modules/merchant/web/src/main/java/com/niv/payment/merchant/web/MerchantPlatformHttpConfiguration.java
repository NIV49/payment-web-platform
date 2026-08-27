package com.niv.payment.merchant.web;

import com.niv.payment.merchant.core.AccountDomain;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration(proxyBeanMethods = false)
@Import({MerchantPlatformController.class, MerchantOnboardingController.class,
    MerchantDocumentController.class, MerchantHttpExceptionHandler.class})
public class MerchantPlatformHttpConfiguration {
    @Bean
    MerchantSubjectAdapter merchantPlatformSubjectAdapter() {
        return new MerchantSubjectAdapter(AccountDomain.PLATFORM);
    }
}
