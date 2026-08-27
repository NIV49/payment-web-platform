package com.niv.payment.merchant.core.crypto;

import java.util.List;

public interface MerchantCryptography {
    RegistrationFingerprint fingerprint(
        String searchKeyId,
        int normalizationVersion,
        String country,
        String registrationNumber
    );

    ProtectedRegistration protect(
        long merchantId,
        long tenantId,
        String country,
        String registrationNumber,
        String searchKeyId,
        String aeadKeyId
    );

    String decryptNormalized(
        long merchantId,
        long tenantId,
        String country,
        ProtectedRegistration protectedRegistration
    );

    IdempotencyDigest idempotencyDigest(
        String keyId,
        int digestSchemeVersion,
        List<String> normalizedCommandFields
    );
}
