package com.niv.payment.merchant.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.niv.payment.merchant.core.MerchantDetail;
import com.niv.payment.merchant.core.MerchantModels;
import com.niv.payment.merchant.core.MerchantMutationResult;
import com.niv.payment.merchant.core.MerchantPage;

import java.util.List;

final class MerchantResponses {
    private MerchantResponses() { }

    static SelfDetail selfDetail(MerchantDetail source) {
        return new SelfDetail(Long.toString(source.merchantId()), Long.toString(source.tenantId()),
            source.merchantCode(), source.legalName(), source.displayName(),
            source.merchantTypeCode(), source.legalPersonName(), source.authenticationType(),
            source.registrationCountry(), source.registrationNumberMasked(),
            source.remarks(), source.statusReasonCode(), source.marketCodes(),
            source.status().name(), source.rowVersion(), text(source.submittedAt()),
            text(source.reviewedAt()), decision(source.lastDecision(), false),
            text(source.createdAt()), text(source.updatedAt()));
    }

    static PlatformDetail platformDetail(MerchantDetail source) {
        return new PlatformDetail(Long.toString(source.merchantId()), Long.toString(source.tenantId()),
            source.merchantCode(), source.legalName(), source.displayName(),
            source.merchantTypeCode(), source.legalPersonName(), source.authenticationType(),
            source.registrationCountry(), source.registrationNumberMasked(),
            source.remarks(), source.statusReasonCode(), source.marketCodes(),
            source.status().name(), source.rowVersion(), text(source.submittedAt()),
            text(source.reviewedAt()), decision(source.lastDecision(), true),
            text(source.createdAt()), text(source.updatedAt()), source.brandName(),
            source.industryCode(), source.registeredAddress(), source.operatingAddress(),
            source.contactEmail(), source.contactPhone(), source.legalIdTypeCode(),
            source.legalIdValidity() == null ? null : new MerchantOnboardingResponses.Validity(
                source.legalIdValidity().validFrom().toString(),
                source.legalIdValidity().validTo().toString()),
            source.legalIdNoMasked(), document(source.brandLogoDocument()),
            document(source.businessLicenseDocument()), document(source.legalIdFrontDocument()),
            document(source.legalIdBackDocument()), document(source.legalIdHoldingDocument()));
    }

    static Mutation mutation(MerchantMutationResult source) {
        return new Mutation(Long.toString(source.merchantId()), source.merchantCode(),
            source.status().name(), source.rowVersion());
    }

    static Page page(MerchantPage source) {
        return new Page(source.items().stream().map(item -> listItem(item,
            source.reviewPendingMerchantIds().contains(item.merchantId()))).toList(), source.total());
    }

    private static ListItem listItem(MerchantDetail source, boolean reviewPending) {
        return new ListItem(Long.toString(source.merchantId()), Long.toString(source.tenantId()),
            source.merchantCode(), source.legalName(), source.displayName(),
            source.merchantTypeCode(), source.legalPersonName(), source.authenticationType(),
            source.registrationCountry(), source.registrationNumberMasked(), source.remarks(),
            source.statusReasonCode(), source.marketCodes(), source.status().name(),
            reviewPending, source.rowVersion(), text(source.submittedAt()), text(source.createdAt()),
            text(source.updatedAt()));
    }

    private static LastDecision decision(MerchantModels.LastDecision source, boolean platform) {
        if (source == null) return null;
        return new LastDecision(source.decision(), source.reasonCode(), text(source.decidedAt()),
            platform && source.decidedByMembershipId() != null
                ? Long.toString(source.decidedByMembershipId()) : null);
    }

    private static MerchantOnboardingResponses.Document document(
        com.niv.payment.merchant.core.MerchantOnboardingModels.DocumentMetadata source
    ) {
        return source == null ? null : new MerchantOnboardingResponses.Document(
            Long.toString(source.documentId()), source.kind().name(), source.mediaType(),
            source.width(), source.height(), source.size());
    }

    private static String text(java.time.OffsetDateTime value) {
        return value == null ? null : value.toInstant().toString();
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    record SelfApplication(SelfDetail merchant) { }
    record Mutation(String merchantId, String merchantCode, String status, long rowVersion) { }
    record Page(List<ListItem> items, long total) { }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    record ListItem(String merchantId, String tenantId, String merchantCode, String legalName,
                    String displayName, String merchantTypeCode, String legalPersonName,
                    String authenticationType, String registrationCountry,
                    String registrationNumberMasked, String remarks, String statusReasonCode,
                    List<String> marketCodes, String status, boolean reviewPending, long rowVersion,
                    String submittedAt, String createdAt, String updatedAt) { }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    record SelfDetail(String merchantId, String tenantId, String merchantCode, String legalName,
                  String displayName, String merchantTypeCode, String legalPersonName,
                  String authenticationType, String registrationCountry,
                  String registrationNumberMasked, String remarks, String statusReasonCode,
                  List<String> marketCodes, String status, long rowVersion,
                  String submittedAt, String reviewedAt, LastDecision lastDecision,
                  String createdAt, String updatedAt) { }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    record PlatformDetail(String merchantId, String tenantId, String merchantCode, String legalName,
                  String displayName, String merchantTypeCode, String legalPersonName,
                  String authenticationType, String registrationCountry,
                  String registrationNumberMasked, String remarks, String statusReasonCode,
                  List<String> marketCodes, String status, long rowVersion,
                  String submittedAt, String reviewedAt, LastDecision lastDecision,
                  String createdAt, String updatedAt, String brandName, String industryCode,
                  String registeredAddress, String operatingAddress, String contactEmail,
                  String contactPhone, String legalIdTypeCode,
                  MerchantOnboardingResponses.Validity legalIdValidity,
                  String legalIdNoMasked,
                  MerchantOnboardingResponses.Document brandLogoDocument,
                  MerchantOnboardingResponses.Document businessLicenseDocument,
                  MerchantOnboardingResponses.Document legalIdFrontDocument,
                  MerchantOnboardingResponses.Document legalIdBackDocument,
                  MerchantOnboardingResponses.Document legalIdHoldingDocument) { }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record LastDecision(String decision, String reasonCode, String decidedAt,
                        String decidedByMembershipId) { }
}
