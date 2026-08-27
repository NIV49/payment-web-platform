package com.niv.payment.merchant.core;

public interface MerchantProtectedDataKeyRotation {
    FullRotationResult rotateAll(
        String targetSearchKeyId,
        String targetRegistrationAeadKeyId,
        String targetLegalIdAeadKeyId,
        String targetDocumentAeadKeyId
    );

    record FullRotationResult(
        String searchKeyId,
        String registrationAeadKeyId,
        String legalIdAeadKeyId,
        String documentAeadKeyId,
        long rotatedRegistrationMerchantCount,
        long rotatedRegistrationAmendmentCount,
        long rotatedLegalIdMerchantCount,
        long rotatedLegalIdAmendmentCount,
        long rotatedDocumentCount
    ) { }
}
