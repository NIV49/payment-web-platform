package com.niv.payment.merchant.core;

import java.time.OffsetDateTime;

public record MerchantDetail(long merchantId, long tenantId, String merchantCode,
                             String legalName, String displayName, String merchantTypeCode,
                             String legalPersonName, String authenticationType,
                             String registrationCountry,
                             String registrationNumberMasked, String remarks,
                             String statusReasonCode, java.util.List<String> marketCodes,
                             MerchantStatus status, long rowVersion,
                             OffsetDateTime submittedAt, OffsetDateTime reviewedAt,
                             MerchantModels.LastDecision lastDecision,
                             OffsetDateTime createdAt, OffsetDateTime updatedAt,
                             String brandName, String industryCode,
                             String registeredAddress, String operatingAddress,
                             String contactEmail, String contactPhone, String legalIdTypeCode,
                             MerchantOnboardingModels.LegalIdValidity legalIdValidity,
                             String legalIdNoMasked,
                             MerchantOnboardingModels.DocumentMetadata brandLogoDocument,
                             MerchantOnboardingModels.DocumentMetadata businessLicenseDocument,
                             MerchantOnboardingModels.DocumentMetadata legalIdFrontDocument,
                             MerchantOnboardingModels.DocumentMetadata legalIdBackDocument,
                             MerchantOnboardingModels.DocumentMetadata legalIdHoldingDocument) {
    public MerchantDetail(long merchantId, long tenantId, String merchantCode,
                          String legalName, String displayName, String registrationCountry,
                          String registrationNumberMasked, MerchantStatus status, long rowVersion,
                          OffsetDateTime submittedAt, OffsetDateTime reviewedAt,
                          MerchantModels.LastDecision lastDecision,
                          OffsetDateTime createdAt, OffsetDateTime updatedAt) {
        this(merchantId, tenantId, merchantCode, legalName, displayName, null, null, null,
            registrationCountry,
            registrationNumberMasked, "", null, java.util.List.of(), status, rowVersion,
            submittedAt, reviewedAt, lastDecision, createdAt, updatedAt,
            null, null, null, null, null, null, null, null, null,
            null, null, null, null, null);
    }

    public MerchantDetail(long merchantId, long tenantId, String merchantCode,
                          String legalName, String displayName, String registrationCountry,
                          String registrationNumberMasked, String remarks,
                          String statusReasonCode, java.util.List<String> marketCodes,
                          MerchantStatus status, long rowVersion,
                          OffsetDateTime submittedAt, OffsetDateTime reviewedAt,
                          MerchantModels.LastDecision lastDecision,
                          OffsetDateTime createdAt, OffsetDateTime updatedAt) {
        this(merchantId, tenantId, merchantCode, legalName, displayName, null, null, null,
            registrationCountry, registrationNumberMasked, remarks, statusReasonCode, marketCodes,
            status, rowVersion, submittedAt, reviewedAt, lastDecision, createdAt, updatedAt,
            null, null, null, null, null, null, null, null, null,
            null, null, null, null, null);
    }

    public MerchantDetail(long merchantId, long tenantId, String merchantCode,
                          String legalName, String displayName, String merchantTypeCode,
                          String legalPersonName, String authenticationType,
                          String registrationCountry, String registrationNumberMasked,
                          String remarks, String statusReasonCode, java.util.List<String> marketCodes,
                          MerchantStatus status, long rowVersion, OffsetDateTime submittedAt,
                          OffsetDateTime reviewedAt, MerchantModels.LastDecision lastDecision,
                          OffsetDateTime createdAt, OffsetDateTime updatedAt) {
        this(merchantId, tenantId, merchantCode, legalName, displayName, merchantTypeCode,
            legalPersonName, authenticationType, registrationCountry, registrationNumberMasked,
            remarks, statusReasonCode, marketCodes, status, rowVersion, submittedAt, reviewedAt,
            lastDecision, createdAt, updatedAt, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null);
    }

    public MerchantDetail {
        marketCodes = java.util.List.copyOf(marketCodes);
    }
}
