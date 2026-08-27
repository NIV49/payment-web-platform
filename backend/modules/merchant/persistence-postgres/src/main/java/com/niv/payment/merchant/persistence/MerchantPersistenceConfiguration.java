package com.niv.payment.merchant.persistence;

import com.niv.payment.merchant.core.MerchantApplicationService;
import com.niv.payment.merchant.core.MerchantRepository;
import com.niv.payment.merchant.core.crypto.MerchantCryptography;
import com.niv.payment.merchant.persistence.crypto.JdkMerchantCryptography;
import com.niv.payment.merchant.persistence.crypto.ThreePurposeKeyRing;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

@Configuration(proxyBeanMethods = false)
public class MerchantPersistenceConfiguration {
    @Bean
    ThreePurposeKeyRing merchantKeyRing(
        @Value("${merchant.protection.search-hmac-keys}") String searchKeys,
        @Value("${merchant.protection.idempotency-hmac-keys}") String idempotencyKeys,
        @Value("${merchant.protection.registration-aead-keys}") String aeadKeys
    ) {
        return ThreePurposeKeyRing.fromBase64(
            configuredKeys(searchKeys), configuredKeys(idempotencyKeys), configuredKeys(aeadKeys));
    }

    @Bean
    MerchantCryptography merchantCryptography(ThreePurposeKeyRing keyRing) {
        return new JdkMerchantCryptography(keyRing);
    }

    @Bean
    MerchantRepository merchantRepository(
        DSLContext dsl,
        MerchantCryptography cryptography,
        MerchantAuditTrace auditTrace,
        @Value("${merchant.protection.active-idempotency-key-id:mch-idempotency-v1}")
        String activeIdempotencyKeyId
    ) {
        return new JooqMerchantRepository(dsl, cryptography,
            activeIdempotencyKeyId, auditTrace::current);
    }

    @Bean
    MerchantApplicationService merchantApplicationService(MerchantRepository repository) {
        return new MerchantApplicationService(repository);
    }

    static Map<String, String> configuredKeys(String configured) {
        if (configured == null || configured.isBlank()) {
            throw new com.niv.payment.merchant.core.MerchantException.ProtectedFieldUnavailable();
        }
        var entries = new java.util.LinkedHashMap<String, String>();
        for (String entry : configured.split(",", -1)) {
            int separator = entry.indexOf('=');
            if (separator <= 0 || separator == entry.length() - 1
                || entries.putIfAbsent(entry.substring(0, separator).trim(),
                    entry.substring(separator + 1).trim()) != null) {
                throw new com.niv.payment.merchant.core.MerchantException.ProtectedFieldUnavailable();
            }
        }
        return Map.copyOf(entries);
    }
}
