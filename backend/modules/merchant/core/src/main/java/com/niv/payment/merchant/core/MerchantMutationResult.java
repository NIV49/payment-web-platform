package com.niv.payment.merchant.core;

public record MerchantMutationResult(long merchantId, String merchantCode,
                                     MerchantStatus status, long rowVersion) { }
