package com.niv.payment.merchant.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.niv.payment.merchant.core.MerchantMutationResult;
import com.niv.payment.merchant.core.MerchantOnboardingModels.Amendment;
import com.niv.payment.merchant.core.MerchantOnboardingModels.AmendmentDecisionView;
import com.niv.payment.merchant.core.MerchantOnboardingModels.AmendmentProfile;
import com.niv.payment.merchant.core.MerchantOnboardingModels.AmendmentResult;
import com.niv.payment.merchant.core.MerchantOnboardingModels.DocumentMetadata;
import com.niv.payment.merchant.core.MerchantOnboardingModels.EligibleTenantPage;

import java.util.List;

final class MerchantOnboardingResponses {
    private MerchantOnboardingResponses() { }

    static EligiblePage eligible(EligibleTenantPage source) {
        return new EligiblePage(source.items().stream().map(item -> new EligibleItem(
            Long.toString(item.tenantId()), item.tenantCode(), item.tenantName())).toList(), source.total());
    }

    static MerchantResponses.Mutation mutation(MerchantMutationResult source) {
        return MerchantResponses.mutation(source);
    }

    static AmendmentSubmission submission(AmendmentResult source) {
        return new AmendmentSubmission(Long.toString(source.amendmentId()),
            Long.toString(source.merchantId()), source.status(), source.rowVersion(),
            source.originMerchantVersion(), source.originStatus().name(), text(source.createdAt()));
    }

    static AmendmentReview review(AmendmentResult source) {
        return new AmendmentReview(Long.toString(source.amendmentId()),
            Long.toString(source.merchantId()), source.status(), source.rowVersion(),
            source.merchantStatus().name(), source.merchantRowVersion());
    }

    static Pending pending(Amendment source) {
        if (source == null) return null;
        return new Pending(Long.toString(source.amendmentId()), Long.toString(source.merchantId()),
            source.status(), source.rowVersion(), source.originMerchantVersion(),
            source.originStatus().name(), Long.toString(source.authorMembershipId()),
            source.canCurrentActorReview(), profile(source.profile()), decision(source.decision()),
            text(source.createdAt()), text(source.updatedAt()));
    }

    static Upload upload(DocumentMetadata source) {
        return new Upload(Long.toString(source.documentId()), source.kind().name(),
            source.mediaType(), source.width(), source.height(), source.size(), text(source.expiresAt()));
    }

    private static PendingProfile profile(AmendmentProfile source) {
        return new PendingProfile(source.displayName(), source.brandName(), source.authenticationType(),
            source.merchantTypeCode(), source.industryCode(), document(source.brandLogoDocument()),
            source.legalName(), source.registrationCountry(), source.registrationNumberMasked(),
            source.marketCodes(), source.registeredAddress(), source.operatingAddress(),
            document(source.businessLicenseDocument()), source.legalPersonName(), source.contactEmail(),
            source.contactPhone(), source.legalIdTypeCode(), source.legalIdNoMasked(),
            new Validity(source.legalIdValidity().validFrom().toString(),
                source.legalIdValidity().validTo().toString()),
            document(source.legalIdFrontDocument()), document(source.legalIdBackDocument()),
            document(source.legalIdHoldingDocument()), source.remarks());
    }

    private static Document document(DocumentMetadata source) {
        return new Document(Long.toString(source.documentId()), source.kind().name(),
            source.mediaType(), source.width(), source.height(), source.size());
    }

    private static Decision decision(AmendmentDecisionView source) {
        return source == null ? null : new Decision(source.decision().name(), source.reasonCode(),
            text(source.decidedAt()));
    }

    private static String text(java.time.OffsetDateTime source) {
        return source == null ? null : source.toInstant().toString();
    }

    record EligiblePage(List<EligibleItem> items, long total) { }
    record EligibleItem(String tenantId, String tenantCode, String tenantName) { }
    record Upload(String documentId, String kind, String mediaType, int width, int height,
                  long sizeBytes, String expiresAt) { }
    record Deleted(String documentId, String status) { }
    record AmendmentSubmission(String amendmentId, String merchantId, String status, long rowVersion,
                               long originMerchantVersion, String originStatus, String createdAt) { }
    record AmendmentReview(String amendmentId, String merchantId, String status, long rowVersion,
                           String merchantStatus, long merchantRowVersion) { }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    record Pending(String amendmentId, String merchantId, String status, long rowVersion,
                   long originMerchantVersion, String originStatus, String authorMembershipId,
                   boolean canCurrentActorReview, PendingProfile profile, Decision decision,
                   String createdAt, String updatedAt) { }
    record PendingProfile(String displayName, String brandName, String authenticationType,
                          String merchantTypeCode, String industryCode, Document brandLogoDocument,
                          String legalName, String registrationCountry, String registrationNumberMasked,
                          List<String> marketCodes, String registeredAddress, String operatingAddress,
                          Document businessLicenseDocument, String legalPersonName, String contactEmail,
                          String contactPhone, String legalIdTypeCode, String legalIdNoMasked,
                          Validity legalIdValidity, Document legalIdFrontDocument,
                          Document legalIdBackDocument, Document legalIdHoldingDocument, String remarks) { }
    record Document(String documentId, String kind, String mediaType, int width, int height,
                    long sizeBytes) { }
    record Validity(String validFrom, String validTo) { }
    record Decision(String decision, String reasonCode, String decidedAt) { }
}
