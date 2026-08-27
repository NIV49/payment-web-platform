package com.niv.payment.merchant.core;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MerchantApplicationServiceTest {
    private final RecordingRepository repository = new RecordingRepository();
    private final MerchantApplicationService service = new MerchantApplicationService(repository);
    private final MerchantActor merchant = MerchantActor.local(11, 12, 13, AccountDomain.MERCHANT, 1, 2, 3);
    private final MerchantActor platform = MerchantActor.localStepUp(
        21, 22, 23, AccountDomain.PLATFORM, 1, 2, 3);

    @Test
    void explicitNullVersionFixesFirstSubmissionBeforeRepositoryAuthorization() {
        var result = service.submit(merchant, new SubmissionRequest(
            UUID.randomUUID(), null, " Example Legal ", " Example ", "SG", " 2026-001 ", true));

        assertEquals(MerchantCommand.SUBMIT, repository.command.command());
        assertEquals("Example Legal", repository.command.legalName());
        assertEquals("2026001", repository.command.normalizedRegistrationNumber());
        assertEquals(MerchantStatus.PENDING_REVIEW, result.status());
        assertFalse(repository.command.toString().contains("2026-001"));
        assertFalse(repository.command.toString().contains("2026001"));
    }

    @Test
    void integerVersionFixesResubmissionAndAllowsOmissionOnlyForRepositoryToCheckCountry() {
        service.submit(merchant, new SubmissionRequest(
            UUID.randomUUID(), 4L, "Legal", "Display", "SG", null, true));
        assertEquals(MerchantCommand.RESUBMIT, repository.command.command());
        assertEquals(4L, repository.command.expectedVersion());
        assertEquals(Optional.empty(), repository.command.normalizedRegistrationNumberOptional());
    }

    @Test
    void rejectsMissingExpectedVersionMemberAndInvalidProfileBeforeRepository() {
        assertThrows(MerchantException.InvalidRequest.class, () -> service.submit(merchant,
            new SubmissionRequest(UUID.randomUUID(), null, "Legal", "Display", "SG", "A", false)));
        assertThrows(MerchantException.InvalidRequest.class, () -> service.submit(merchant,
            new SubmissionRequest(UUID.randomUUID(), null, " ", "Display", "SG", "A", true)));
    }

    @Test
    void boundsPlatformDirectoryFiltersBeforeRepository() {
        assertThrows(MerchantException.InvalidRequest.class, () -> service.findPlatform(platform,
            new MerchantQuery("M".repeat(65), null, null, null, null, null, 1, 20)));
        assertThrows(MerchantException.InvalidRequest.class, () -> service.findPlatform(platform,
            new MerchantQuery(null, "N".repeat(201), null, null, null, null, 1, 20)));
    }

    @Test
    void platformProfileUpdateTrimsTextAndCanonicalizesMarketsBeforeRepository() {
        UUID key = UUID.randomUUID();

        service.updateProfile(platform, 101, new ProfileUpdateRequest(
            4, key, " Example Legal ", " Example ", "DIRECT",
            "\u2003Example Director\u2003", "ENTERPRISE", " note ",
            List.of("PHL", "BRA")));

        assertEquals(101, repository.profileUpdate.merchantId());
        assertEquals(4, repository.profileUpdate.expectedVersion());
        assertEquals(key, repository.profileUpdate.idempotencyKey());
        assertEquals("Example Legal", repository.profileUpdate.legalName());
        assertEquals("Example", repository.profileUpdate.displayName());
        assertEquals("DIRECT", repository.profileUpdate.merchantTypeCode());
        assertEquals("Example Director", repository.profileUpdate.legalPersonName());
        assertEquals("ENTERPRISE", repository.profileUpdate.authenticationType());
        assertEquals("note", repository.profileUpdate.remarks());
        assertEquals(List.of("BRA", "PHL"), repository.profileUpdate.marketCodes());
    }

    @Test
    void platformProfileUpdatePropagatesRepositoryStateConflictForSemanticNoop() {
        repository.rejectProfileAsNoop = true;

        assertThrows(MerchantException.StateConflict.class, () -> service.updateProfile(platform, 101,
            new ProfileUpdateRequest(4, UUID.randomUUID(), "Legal", "Display", "DIRECT",
                "Director", "ENTERPRISE", "", List.of("BRA"))));
    }

    @Test
    void platformProfileUpdateRejectsUnknownDuplicateOrMalformedMarketsBeforeRepository() {
        assertThrows(MerchantException.InvalidRequest.class, () -> service.updateProfile(platform, 101,
            new ProfileUpdateRequest(4, UUID.randomUUID(), "Legal", "Display", "DIRECT",
                "Director", "ENTERPRISE", "", List.of("BRA", "BRA"))));
        assertThrows(MerchantException.InvalidRequest.class, () -> service.updateProfile(platform, 101,
            new ProfileUpdateRequest(4, UUID.randomUUID(), "Legal", "Display", "DIRECT",
                "Director", "ENTERPRISE", "", List.of("USA"))));
        assertThrows(MerchantException.InvalidRequest.class, () -> service.updateProfile(platform, 101,
            new ProfileUpdateRequest(4, UUID.randomUUID(), "Legal", "Display", "DIRECT",
                "Director", "ENTERPRISE", "", List.of("bra"))));
    }

    @Test
    void platformProfileUpdateUsesUnicodeTrimAndCodePointRemarksLimit() {
        service.updateProfile(platform, 101, new ProfileUpdateRequest(
            4, UUID.randomUUID(), "Legal", "Display", "DIRECT", "Director", "ENTERPRISE",
            "\u2003note\u2003", List.of("BRA")));
        assertEquals("note", repository.profileUpdate.remarks());

        assertThrows(MerchantException.InvalidRequest.class, () -> service.updateProfile(platform, 101,
            new ProfileUpdateRequest(4, UUID.randomUUID(), "Legal", "Display",
                "DIRECT", "Director", "ENTERPRISE", "\ud83d\udcb3".repeat(301),
                List.of("BRA"))));
    }

    @Test
    void platformProfileUpdateRejectsMissingOrUnknownClassificationsAndBoundsLegalPerson() {
        assertThrows(MerchantException.InvalidRequest.class, () -> service.updateProfile(platform, 101,
            new ProfileUpdateRequest(4, UUID.randomUUID(), "Legal", "Display", null,
                "Director", "ENTERPRISE", "", List.of("BRA"))));
        assertThrows(MerchantException.InvalidRequest.class, () -> service.updateProfile(platform, 101,
            new ProfileUpdateRequest(4, UUID.randomUUID(), "Legal", "Display", "UNKNOWN",
                "Director", "ENTERPRISE", "", List.of("BRA"))));
        assertThrows(MerchantException.InvalidRequest.class, () -> service.updateProfile(platform, 101,
            new ProfileUpdateRequest(4, UUID.randomUUID(), "Legal", "Display", "DIRECT",
                " ", "ENTERPRISE", "", List.of("BRA"))));
        assertThrows(MerchantException.InvalidRequest.class, () -> service.updateProfile(platform, 101,
            new ProfileUpdateRequest(4, UUID.randomUUID(), "Legal", "Display", "DIRECT",
                "\ud83d\udcb3".repeat(201), "ENTERPRISE", "", List.of("BRA"))));
        assertThrows(MerchantException.InvalidRequest.class, () -> service.updateProfile(platform, 101,
            new ProfileUpdateRequest(4, UUID.randomUUID(), "Legal", "Display", "DIRECT",
                "Director", "UNKNOWN", "", List.of("BRA"))));
    }

    @Test
    void platformDirectoryValidatesClassificationFiltersBeforeRepository() {
        service.findPlatform(platform, new MerchantQuery(null, null, null, null, null,
            "DIRECT", "ENTERPRISE", null, null, 1, 20));
        assertEquals("DIRECT", repository.query.merchantTypeCode());
        assertEquals("ENTERPRISE", repository.query.authenticationType());

        assertThrows(MerchantException.InvalidRequest.class, () -> service.findPlatform(platform,
            new MerchantQuery(null, null, null, null, null, "UNKNOWN", null,
                null, null, 1, 20)));
        assertThrows(MerchantException.InvalidRequest.class, () -> service.findPlatform(platform,
            new MerchantQuery(null, null, null, null, null, null, "UNKNOWN",
                null, null, 1, 20)));
    }

    private static final class RecordingRepository implements MerchantRepository {
        private SubmissionCommand command;
        private ProfileUpdateCommand profileUpdate;
        private MerchantQuery query;
        private boolean rejectProfileAsNoop;

        @Override
        public Optional<MerchantDetail> findSelf(MerchantActor actor) {
            return Optional.empty();
        }

        @Override
        public MerchantPage findPlatform(MerchantActor actor, MerchantQuery query) {
            this.query = query;
            return new MerchantPage(java.util.List.of(), 0);
        }

        @Override
        public MerchantDetail findPlatformDetail(MerchantActor actor, long merchantId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public MerchantMutationResult submit(MerchantActor actor, SubmissionCommand command) {
            this.command = command;
            return new MerchantMutationResult(101, "MCH_TEST", MerchantStatus.PENDING_REVIEW, 0);
        }

        @Override
        public MerchantMutationResult transition(MerchantActor actor, TransitionCommand command) {
            throw new UnsupportedOperationException();
        }

        @Override
        public MerchantMutationResult updateProfile(MerchantActor actor, ProfileUpdateCommand command) {
            this.profileUpdate = command;
            if (rejectProfileAsNoop) throw new MerchantException.StateConflict();
            return new MerchantMutationResult(command.merchantId(), "MCH_TEST",
                MerchantStatus.ACTIVE, command.expectedVersion() + 1);
        }
    }
}
