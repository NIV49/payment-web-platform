package com.niv.payment.merchant.core;

import java.time.OffsetDateTime;

public record MerchantQuery(String merchantCode, String name, MerchantStatus status,
                            String registrationCountry, String marketCode,
                            String merchantTypeCode, String authenticationType,
                            OffsetDateTime createdFrom, OffsetDateTime createdTo,
                            int page, int pageSize) {
    public MerchantQuery(String merchantCode, String name, MerchantStatus status,
                         String registrationCountry, String marketCode, OffsetDateTime createdFrom,
                         OffsetDateTime createdTo, int page, int pageSize) {
        this(merchantCode, name, status, registrationCountry, marketCode, null, null,
            createdFrom, createdTo, page, pageSize);
    }

    public MerchantQuery(String merchantCode, String name, MerchantStatus status,
                         String registrationCountry, OffsetDateTime createdFrom,
                         OffsetDateTime createdTo, int page, int pageSize) {
        this(merchantCode, name, status, registrationCountry, null, null, null,
            createdFrom, createdTo, page, pageSize);
    }
}
