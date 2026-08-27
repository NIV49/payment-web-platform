package com.niv.payment.merchant.core;

public interface MerchantRegistrationKeyRotation {
    RotationResult rotate(String targetSearchKeyId, String targetAeadKeyId);

    record RotationResult(String searchKeyId, String aeadKeyId, long rotatedMerchantCount) { }
}
