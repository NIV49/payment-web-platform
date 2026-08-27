package com.niv.payment.merchant.core;

import java.util.Optional;
import java.util.List;

import com.niv.payment.merchant.core.MerchantOnboardingModels.Amendment;
import com.niv.payment.merchant.core.MerchantOnboardingModels.AmendmentRequest;
import com.niv.payment.merchant.core.MerchantOnboardingModels.AmendmentResult;
import com.niv.payment.merchant.core.MerchantOnboardingModels.AmendmentReviewRequest;
import com.niv.payment.merchant.core.MerchantOnboardingModels.CreateRequest;
import com.niv.payment.merchant.core.MerchantOnboardingModels.DocumentContent;
import com.niv.payment.merchant.core.MerchantOnboardingModels.DocumentKind;
import com.niv.payment.merchant.core.MerchantOnboardingModels.DocumentMetadata;
import com.niv.payment.merchant.core.MerchantOnboardingModels.DocumentUploadRequest;
import com.niv.payment.merchant.core.MerchantOnboardingModels.EligibleTenant;
import com.niv.payment.merchant.core.MerchantOnboardingModels.EligibleTenantPage;
import com.niv.payment.merchant.core.MerchantOnboardingModels.EligibleTenantQuery;

public interface MerchantRepository {
    Optional<MerchantDetail> findSelf(MerchantActor actor);
    MerchantPage findPlatform(MerchantActor actor, MerchantQuery query);
    MerchantDetail findPlatformDetail(MerchantActor actor, long merchantId);
    MerchantMutationResult submit(MerchantActor actor, SubmissionCommand command);
    MerchantMutationResult updateProfile(MerchantActor actor, ProfileUpdateCommand command);
    MerchantMutationResult transition(MerchantActor actor, TransitionCommand command);
    default EligibleTenantPage findEligibleTenants(MerchantActor actor, EligibleTenantQuery query) {
        throw new UnsupportedOperationException();
    }
    default DocumentMetadata uploadDocument(MerchantActor actor, DocumentUploadRequest request) {
        throw new UnsupportedOperationException();
    }
    default DocumentContent readStagedDocument(MerchantActor actor, long documentId) {
        throw new UnsupportedOperationException();
    }
    default void deleteDocument(MerchantActor actor, long documentId) {
        throw new UnsupportedOperationException();
    }
    default DocumentContent readMerchantDocument(
        MerchantActor actor, long merchantId, DocumentKind kind, Long amendmentId
    ) {
        throw new UnsupportedOperationException();
    }
    default MerchantMutationResult create(MerchantActor actor, CreateRequest request) {
        throw new UnsupportedOperationException();
    }
    default AmendmentResult createAmendment(MerchantActor actor, AmendmentRequest request) {
        throw new UnsupportedOperationException();
    }
    default Amendment findPendingAmendment(MerchantActor actor, long merchantId) {
        throw new UnsupportedOperationException();
    }
    default AmendmentResult reviewAmendment(
        MerchantActor actor, AmendmentReviewRequest request
    ) {
        throw new UnsupportedOperationException();
    }
}
