package com.niv.payment.merchant.core;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class MerchantOnboardingModels {
    private MerchantOnboardingModels() { }

    public enum DocumentKind {
        BRAND_LOGO,
        BUSINESS_LICENSE,
        LEGAL_ID_FRONT,
        LEGAL_ID_BACK,
        LEGAL_ID_HOLDING
    }

    public enum SensitiveMode { RETAIN, REPLACE }

    public enum AmendmentDecision { APPROVE, REJECT }

    public record SensitiveValue(SensitiveMode mode, String value) { }

    public record LegalIdValidity(LocalDate validFrom, LocalDate validTo) { }

    public record Profile(
        String displayName,
        String brandName,
        String authenticationType,
        String merchantTypeCode,
        String industryCode,
        Long brandLogoDocumentId,
        String legalName,
        String registrationCountry,
        List<String> marketCodes,
        String registeredAddress,
        String operatingAddress,
        Long businessLicenseDocumentId,
        String legalPersonName,
        String contactEmail,
        String contactPhone,
        String legalIdTypeCode,
        SensitiveValue legalIdNo,
        LegalIdValidity legalIdValidity,
        Long legalIdFrontDocumentId,
        Long legalIdBackDocumentId,
        Long legalIdHoldingDocumentId,
        String remarks,
        SensitiveValue registrationNumber
    ) { }

    public record CreateRequest(long targetTenantId, UUID idempotencyKey, Profile profile) { }

    public record AmendmentRequest(
        long merchantId,
        long expectedVersion,
        UUID idempotencyKey,
        Profile profile
    ) { }

    public record AmendmentReviewRequest(
        long merchantId,
        long amendmentId,
        long expectedVersion,
        UUID idempotencyKey,
        AmendmentDecision decision,
        String reasonCode
    ) { }

    public record EligibleTenant(long tenantId, String tenantCode, String tenantName) { }

    public record EligibleTenantQuery(String tenantCode, String tenantName, int page, int pageSize) { }

    public record EligibleTenantPage(List<EligibleTenant> items, long total) { }

    public record DocumentUploadRequest(
        long targetTenantId,
        DocumentKind kind,
        String mediaType,
        byte[] normalizedContent,
        int width,
        int height
    ) {
        public DocumentUploadRequest {
            normalizedContent = normalizedContent == null ? null : normalizedContent.clone();
        }

        @Override
        public byte[] normalizedContent() {
            return normalizedContent == null ? null : normalizedContent.clone();
        }
    }

    public record DocumentMetadata(
        long documentId,
        long targetTenantId,
        DocumentKind kind,
        String mediaType,
        int width,
        int height,
        long size,
        OffsetDateTime expiresAt,
        boolean attached
    ) { }

    public record DocumentContent(String mediaType, byte[] content) {
        public DocumentContent {
            content = content == null ? null : content.clone();
        }

        @Override
        public byte[] content() {
            return content == null ? null : content.clone();
        }
    }

    public record Amendment(
        long amendmentId,
        long merchantId,
        String status,
        long rowVersion,
        long originMerchantVersion,
        MerchantStatus originStatus,
        long authorMembershipId,
        boolean canCurrentActorReview,
        AmendmentProfile profile,
        AmendmentDecisionView decision,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
    ) { }

    public record AmendmentProfile(
        String displayName,
        String brandName,
        String authenticationType,
        String merchantTypeCode,
        String industryCode,
        DocumentMetadata brandLogoDocument,
        String legalName,
        String registrationCountry,
        String registrationNumberMasked,
        List<String> marketCodes,
        String registeredAddress,
        String operatingAddress,
        DocumentMetadata businessLicenseDocument,
        String legalPersonName,
        String contactEmail,
        String contactPhone,
        String legalIdTypeCode,
        String legalIdNoMasked,
        LegalIdValidity legalIdValidity,
        DocumentMetadata legalIdFrontDocument,
        DocumentMetadata legalIdBackDocument,
        DocumentMetadata legalIdHoldingDocument,
        String remarks
    ) { }

    public record AmendmentDecisionView(
        AmendmentDecision decision,
        String reasonCode,
        OffsetDateTime decidedAt
    ) { }

    public record AmendmentResult(
        long amendmentId,
        long merchantId,
        String status,
        long rowVersion,
        MerchantStatus merchantStatus,
        long merchantRowVersion,
        long originMerchantVersion,
        MerchantStatus originStatus,
        OffsetDateTime createdAt
    ) { }
}
