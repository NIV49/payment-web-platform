package com.niv.payment.merchant.persistence;

import com.niv.payment.merchant.core.MerchantApplicationService;
import com.niv.payment.merchant.core.MerchantOnboardingService;
import com.niv.payment.merchant.core.MerchantRepository;
import com.niv.payment.merchant.core.crypto.MerchantCryptography;
import com.niv.payment.merchant.core.crypto.MerchantOnboardingCryptography;
import com.niv.payment.merchant.persistence.crypto.JdkMerchantCryptography;
import com.niv.payment.merchant.persistence.crypto.JdkMerchantOnboardingCryptography;
import com.niv.payment.merchant.persistence.crypto.MerchantOnboardingKeyRing;
import com.niv.payment.merchant.persistence.crypto.ThreePurposeKeyRing;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration(proxyBeanMethods = false)
public class MerchantPlatformPersistenceConfiguration {
    @Bean
    ThreePurposeKeyRing merchantPlatformKeyRing(
        @Value("${merchant.protection.search-hmac-keys}") String searchKeys,
        @Value("${merchant.protection.idempotency-hmac-keys}") String idempotencyKeys,
        @Value("${merchant.protection.registration-aead-keys}") String registrationKeys
    ) {
        return ThreePurposeKeyRing.fromBase64(
            MerchantPersistenceConfiguration.configuredKeys(searchKeys),
            MerchantPersistenceConfiguration.configuredKeys(idempotencyKeys),
            MerchantPersistenceConfiguration.configuredKeys(registrationKeys));
    }

    @Bean
    MerchantCryptography merchantPlatformCryptography(ThreePurposeKeyRing keyRing) {
        return new JdkMerchantCryptography(keyRing);
    }

    @Bean
    MerchantOnboardingKeyRing merchantOnboardingKeyRing(
        @Value("${merchant.protection.search-hmac-keys}") String searchKeys,
        @Value("${merchant.protection.idempotency-hmac-keys}") String idempotencyKeys,
        @Value("${merchant.protection.registration-aead-keys}") String registrationKeys,
        @Value("${merchant.protection.legal-id-aead-keys}") String legalIdKeys,
        @Value("${merchant.protection.document-aead-keys}") String documentKeys
    ) {
        return MerchantOnboardingKeyRing.fromBase64(
            MerchantPersistenceConfiguration.configuredKeys(legalIdKeys),
            MerchantPersistenceConfiguration.configuredKeys(documentKeys),
            List.of(MerchantPersistenceConfiguration.configuredKeys(searchKeys),
                MerchantPersistenceConfiguration.configuredKeys(idempotencyKeys),
                MerchantPersistenceConfiguration.configuredKeys(registrationKeys)));
    }

    @Bean
    MerchantOnboardingCryptography merchantOnboardingCryptography(
        MerchantOnboardingKeyRing keyRing
    ) {
        return new JdkMerchantOnboardingCryptography(keyRing);
    }

    @Bean
    JooqMerchantRepository merchantPlatformRepository(
        DSLContext dsl, MerchantCryptography cryptography,
        MerchantOnboardingCryptography onboardingCryptography, MerchantAuditTrace auditTrace,
        @Value("${merchant.protection.active-idempotency-key-id:mch-idempotency-v1}") String keyId
    ) {
        return new JooqMerchantRepository(dsl, cryptography, onboardingCryptography,
            keyId, auditTrace::current);
    }

    @Bean MerchantApplicationService merchantApplicationService(MerchantRepository repository) {
        return new MerchantApplicationService(repository);
    }

    @Bean MerchantOnboardingService merchantOnboardingService(MerchantRepository repository) {
        return new MerchantOnboardingService(repository);
    }
}
