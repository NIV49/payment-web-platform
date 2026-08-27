package com.niv.payment.merchant.core;

import com.niv.payment.merchant.core.MerchantOnboardingModels.CreateRequest;
import com.niv.payment.merchant.core.MerchantOnboardingModels.DocumentKind;
import com.niv.payment.merchant.core.MerchantOnboardingModels.DocumentUploadRequest;
import com.niv.payment.merchant.core.MerchantOnboardingModels.LegalIdValidity;
import com.niv.payment.merchant.core.MerchantOnboardingModels.Profile;
import com.niv.payment.merchant.core.MerchantOnboardingModels.SensitiveMode;
import com.niv.payment.merchant.core.MerchantOnboardingModels.SensitiveValue;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MerchantOnboardingServiceTest {
    private final MerchantOnboardingService service = new MerchantOnboardingService(new MerchantRepository() {
        @Override public java.util.Optional<MerchantDetail> findSelf(MerchantActor actor) { throw new UnsupportedOperationException(); }
        @Override public MerchantPage findPlatform(MerchantActor actor, MerchantQuery query) { throw new UnsupportedOperationException(); }
        @Override public MerchantDetail findPlatformDetail(MerchantActor actor, long merchantId) { throw new UnsupportedOperationException(); }
        @Override public MerchantMutationResult submit(MerchantActor actor, SubmissionCommand command) { throw new UnsupportedOperationException(); }
        @Override public MerchantMutationResult updateProfile(MerchantActor actor, ProfileUpdateCommand command) { throw new UnsupportedOperationException(); }
        @Override public MerchantMutationResult transition(MerchantActor actor, TransitionCommand command) { throw new UnsupportedOperationException(); }
    });
    private final MerchantActor platform = MerchantActor.localStepUp(
        1, 1000, 1, AccountDomain.PLATFORM, 1, 1, 1);

    @Test
    void rejectsMissingRequiredProfileAndDocumentFieldsBeforePersistence() {
        Profile profile = validProfile();
        Profile missingLogo = new Profile(profile.displayName(), profile.brandName(),
            profile.authenticationType(), profile.merchantTypeCode(), profile.industryCode(), null,
            profile.legalName(), profile.registrationCountry(), profile.marketCodes(),
            profile.registeredAddress(), profile.operatingAddress(), profile.businessLicenseDocumentId(),
            profile.legalPersonName(), profile.contactEmail(), profile.contactPhone(),
            profile.legalIdTypeCode(), profile.legalIdNo(), profile.legalIdValidity(),
            profile.legalIdFrontDocumentId(), profile.legalIdBackDocumentId(),
            profile.legalIdHoldingDocumentId(), profile.remarks(), profile.registrationNumber());

        assertThrows(MerchantException.InvalidRequest.class, () -> service.create(platform,
            new CreateRequest(2, UUID.randomUUID(), missingLogo)));
    }

    @Test
    void rejectsNonCanonicalTypeIndustryLegalIdAndNonE164Phone() {
        Profile source = validProfile();
        Profile invalid = new Profile(source.displayName(), source.brandName(),
            source.authenticationType(), "DIRECT", "BANK", source.brandLogoDocumentId(),
            source.legalName(), source.registrationCountry(), source.marketCodes(),
            source.registeredAddress(), source.operatingAddress(), source.businessLicenseDocumentId(),
            source.legalPersonName(), source.contactEmail(), "5511000", "RG",
            source.legalIdNo(), source.legalIdValidity(), source.legalIdFrontDocumentId(),
            source.legalIdBackDocumentId(), source.legalIdHoldingDocumentId(), source.remarks(),
            source.registrationNumber());

        assertThrows(MerchantException.InvalidRequest.class, () -> service.create(platform,
            new CreateRequest(2, UUID.randomUUID(), invalid)));
    }

    @Test
    void rejectsHostileImageDimensionsAndPixelCount() {
        assertThrows(MerchantException.InvalidRequest.class, () -> service.uploadDocument(platform,
            new DocumentUploadRequest(2, DocumentKind.BRAND_LOGO, "image/png",
                new byte[]{1}, 4096, 4096)));
    }

    @Test
    void rejectsJsonNullRemarksButAllowsTheRequiredEmptyStringMember() {
        Profile source = validProfile();
        Profile invalid = new Profile(source.displayName(), source.brandName(),
            source.authenticationType(), source.merchantTypeCode(), source.industryCode(),
            source.brandLogoDocumentId(), source.legalName(), source.registrationCountry(),
            source.marketCodes(), source.registeredAddress(), source.operatingAddress(),
            source.businessLicenseDocumentId(), source.legalPersonName(), source.contactEmail(),
            source.contactPhone(), source.legalIdTypeCode(), source.legalIdNo(),
            source.legalIdValidity(), source.legalIdFrontDocumentId(),
            source.legalIdBackDocumentId(), source.legalIdHoldingDocumentId(), null,
            source.registrationNumber());

        assertThrows(MerchantException.InvalidRequest.class, () -> service.create(platform,
            new CreateRequest(2, UUID.randomUUID(), invalid)));
    }

    @Test
    void validatesAndForwardsTheOptionalPendingAmendmentSelector() {
        AtomicReference<Long> amendment = new AtomicReference<>();
        MerchantRepository recording = new MerchantRepository() {
            @Override public java.util.Optional<MerchantDetail> findSelf(MerchantActor actor) { throw new UnsupportedOperationException(); }
            @Override public MerchantPage findPlatform(MerchantActor actor, MerchantQuery query) { throw new UnsupportedOperationException(); }
            @Override public MerchantDetail findPlatformDetail(MerchantActor actor, long merchantId) { throw new UnsupportedOperationException(); }
            @Override public MerchantMutationResult submit(MerchantActor actor, SubmissionCommand command) { throw new UnsupportedOperationException(); }
            @Override public MerchantMutationResult updateProfile(MerchantActor actor, ProfileUpdateCommand command) { throw new UnsupportedOperationException(); }
            @Override public MerchantMutationResult transition(MerchantActor actor, TransitionCommand command) { throw new UnsupportedOperationException(); }
            @Override public MerchantOnboardingModels.DocumentContent readMerchantDocument(
                MerchantActor actor, long merchantId, DocumentKind kind, Long amendmentId
            ) {
                amendment.set(amendmentId);
                return new MerchantOnboardingModels.DocumentContent("image/png", new byte[]{1});
            }
        };
        MerchantOnboardingService recordingService = new MerchantOnboardingService(recording);

        recordingService.merchantDocumentContent(platform, 91, DocumentKind.BRAND_LOGO, null);
        assert amendment.get() == null;
        recordingService.merchantDocumentContent(platform, 91, DocumentKind.BRAND_LOGO, 72L);
        assert amendment.get().equals(72L);
        assertThrows(MerchantException.InvalidRequest.class, () ->
            recordingService.merchantDocumentContent(platform, 91, DocumentKind.BRAND_LOGO, 0L));
    }

    @Test
    void canonicalizesReplacementSecretsOnceBeforeRepositoryAndDigestBoundaries() {
        AtomicReference<Profile> persisted = new AtomicReference<>();
        MerchantRepository recording = new MerchantRepository() {
            @Override public java.util.Optional<MerchantDetail> findSelf(MerchantActor actor) { throw new UnsupportedOperationException(); }
            @Override public MerchantPage findPlatform(MerchantActor actor, MerchantQuery query) { throw new UnsupportedOperationException(); }
            @Override public MerchantDetail findPlatformDetail(MerchantActor actor, long merchantId) { throw new UnsupportedOperationException(); }
            @Override public MerchantMutationResult submit(MerchantActor actor, SubmissionCommand command) { throw new UnsupportedOperationException(); }
            @Override public MerchantMutationResult updateProfile(MerchantActor actor, ProfileUpdateCommand command) { throw new UnsupportedOperationException(); }
            @Override public MerchantMutationResult transition(MerchantActor actor, TransitionCommand command) { throw new UnsupportedOperationException(); }
            @Override public MerchantMutationResult create(MerchantActor actor, CreateRequest request) {
                persisted.set(request.profile());
                return new MerchantMutationResult(91, "MCH0000000091",
                    MerchantStatus.PENDING_REVIEW, 0);
            }
        };
        MerchantOnboardingService recordingService = new MerchantOnboardingService(recording);
        Profile source = validProfile();
        Profile formatted = new Profile(source.displayName(), source.brandName(),
            source.authenticationType(), source.merchantTypeCode(), source.industryCode(),
            source.brandLogoDocumentId(), source.legalName(), source.registrationCountry(),
            source.marketCodes(), source.registeredAddress(), source.operatingAddress(),
            source.businessLicenseDocumentId(), source.legalPersonName(), source.contactEmail(),
            source.contactPhone(), source.legalIdTypeCode(),
            new SensitiveValue(SensitiveMode.REPLACE, " legal / id-0001 "),
            source.legalIdValidity(), source.legalIdFrontDocumentId(),
            source.legalIdBackDocumentId(), source.legalIdHoldingDocumentId(), source.remarks(),
            new SensitiveValue(SensitiveMode.REPLACE, " reg. no / 0001 "));

        recordingService.create(platform, new CreateRequest(2, UUID.randomUUID(), formatted));

        assertEquals("LEGALID0001", persisted.get().legalIdNo().value());
        assertEquals("REGNO0001", persisted.get().registrationNumber().value());
    }

    private static Profile validProfile() {
        return new Profile("Example Pay", "Example", "ENTERPRISE", "PLATFORM",
            "FINANCIAL_SERVICES", 101L, "Example Payments Ltd.", "BR",
            List.of("BRA", "PHL"), "Registered address", "Operating address", 102L,
            "Example Director", "merchant-contact@example.test", "+551100000000",
            "NATIONAL_ID", new SensitiveValue(SensitiveMode.REPLACE, "SYNTHETIC-ID-0001"),
            new LegalIdValidity(LocalDate.parse("2026-01-01"), LocalDate.parse("2036-01-01")),
            103L, 104L, 105L, "", new SensitiveValue(SensitiveMode.REPLACE, "SYNTHETIC-REG-0001"));
    }
}
