package com.niv.payment.merchant.web;

import com.niv.payment.merchant.core.AccountDomain;
import com.niv.payment.merchant.core.MerchantActor;
import com.niv.payment.merchant.core.MerchantApplicationService;
import com.niv.payment.merchant.core.MerchantCommand;
import com.niv.payment.merchant.core.MerchantDetail;
import com.niv.payment.merchant.core.MerchantException;
import com.niv.payment.merchant.core.MerchantMutationResult;
import com.niv.payment.merchant.core.MerchantPage;
import com.niv.payment.merchant.core.MerchantQuery;
import com.niv.payment.merchant.core.MerchantRepository;
import com.niv.payment.merchant.core.MerchantStatus;
import com.niv.payment.merchant.core.ProfileUpdateCommand;
import com.niv.payment.merchant.core.SubmissionCommand;
import com.niv.payment.merchant.core.TransitionCommand;
import com.niv.payment.permission.domain.AuthorizationSubject;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Proxy;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MerchantHttpContractTest {
    private static final UUID KEY = UUID.fromString("8ce154cf-4f13-4aac-b0de-74922513a14f");
    private final ObjectMapper json = new ObjectMapper();
    private final RecordingRepository repository = new RecordingRepository();
    private final MerchantApplicationService service = new MerchantApplicationService(repository);

    @Test
    void explicitNullExpectedVersionSelectsSubmitAndMissingExpectedVersionIsRejected() throws Exception {
        var controller = new MerchantSelfController(service,
            new MerchantSubjectAdapter(AccountDomain.MERCHANT));

        controller.submit(json.readTree("""
            {"idempotencyKey":"8ce154cf-4f13-4aac-b0de-74922513a14f",
             "expectedVersion":null,"legalName":"Example Legal","displayName":"Example",
             "registrationCountry":"SG","registrationNumber":"2026-001234-Z"}
            """), request(subject()));

        assertThat(repository.submission.command()).isEqualTo(MerchantCommand.SUBMIT);
        assertThat(repository.submission.expectedVersion()).isNull();

        assertThatThrownBy(() -> controller.submit(json.readTree("""
            {"idempotencyKey":"8ce154cf-4f13-4aac-b0de-74922513a14f",
             "legalName":"Example Legal","displayName":"Example",
             "registrationCountry":"SG","registrationNumber":"2026-001234-Z"}
            """), request(subject())))
            .isInstanceOf(MerchantException.InvalidRequest.class);
    }

    @Test
    void integralExpectedVersionSelectsResubmitAndMayOmitRegistrationNumber() throws Exception {
        var controller = new MerchantSelfController(service,
            new MerchantSubjectAdapter(AccountDomain.MERCHANT));

        controller.submit(json.readTree("""
            {"idempotencyKey":"8ce154cf-4f13-4aac-b0de-74922513a14f",
             "expectedVersion":3,"legalName":"Example Legal","displayName":"Example",
             "registrationCountry":"SG"}
            """), request(subject()));

        assertThat(repository.submission.command()).isEqualTo(MerchantCommand.RESUBMIT);
        assertThat(repository.submission.expectedVersion()).isEqualTo(3);
        assertThat(repository.submission.normalizedRegistrationNumber()).isNull();
    }

    @Test
    void unknownFieldsExplicitNullRegistrationAndMalformedScalarTypesFailClosed() throws Exception {
        var controller = new MerchantSelfController(service,
            new MerchantSubjectAdapter(AccountDomain.MERCHANT));
        assertThatThrownBy(() -> controller.submit(json.readTree("""
            {"idempotencyKey":"8ce154cf-4f13-4aac-b0de-74922513a14f",
             "expectedVersion":0,"legalName":"Example Legal","displayName":"Example",
             "registrationCountry":"SG","tenantId":"8"}
            """), request(subject())))
            .isInstanceOf(MerchantException.InvalidRequest.class);
        assertThatThrownBy(() -> controller.submit(json.readTree("""
            {"idempotencyKey":"8ce154cf-4f13-4aac-b0de-74922513a14f",
             "expectedVersion":0,"legalName":"Example Legal","displayName":"Example",
             "registrationCountry":"SG","registrationNumber":null}
            """), request(subject())))
            .isInstanceOf(MerchantException.InvalidRequest.class);
        assertThatThrownBy(() -> controller.submit(json.readTree("""
            {"idempotencyKey":"8ce154cf-4f13-4aac-b0de-74922513a14f",
             "expectedVersion":1.0,"legalName":"Example Legal","displayName":"Example",
             "registrationCountry":"SG"}
            """), request(subject())))
            .isInstanceOf(MerchantException.InvalidRequest.class);
    }

    @Test
    void trustedAuthorizationSubjectIsMappedWithoutDroppingIdentityOrVersionFacts() {
        var adapter = new MerchantSubjectAdapter(AccountDomain.PLATFORM);
        AuthorizationSubject source = new AuthorizationSubject(11, 12, 13, 14L,
            15, 16, 17, "https://issuer.example", "subject-18", true, true);

        MerchantActor actor = adapter.actor(request(source));

        assertThat(actor).isEqualTo(new MerchantActor(11, 12, 13, AccountDomain.PLATFORM,
            15, 16, 17, "https://issuer.example", "subject-18", true, true));
        assertThatThrownBy(() -> adapter.actor(request(null)))
            .isInstanceOf(MerchantException.PermissionDenied.class);

        AuthorizationSubject localWithRecordedMapping = new AuthorizationSubject(21, 22, 23,
            null, 24, 25, 26, "https://issuer.example", "local-subject", false, false);
        assertThat(adapter.actor(request(localWithRecordedMapping)))
            .isEqualTo(new MerchantActor(21, 22, 23, AccountDomain.PLATFORM, 24, 25, 26,
                "https://issuer.example", "local-subject", false, false));
    }

    @Test
    void platformControllerMapsEveryFrozenTransitionWithoutAcceptingTargetStatus() throws Exception {
        var controller = new MerchantPlatformController(service,
            new MerchantSubjectAdapter(AccountDomain.PLATFORM));
        HttpServletRequest request = request(subject());

        controller.review(91, json.readTree("""
            {"decision":"APPROVE","reasonCode":"PROFILE_VERIFIED","expectedVersion":0,
             "idempotencyKey":"8ce154cf-4f13-4aac-b0de-74922513a14f"}
            """), request);
        assertThat(repository.transition.command()).isEqualTo(MerchantCommand.APPROVE);

        controller.disable(91, transition("COMPLIANCE_HOLD", 1), request);
        assertThat(repository.transition.command()).isEqualTo(MerchantCommand.DISABLE);
        controller.enable(91, transition("COMPLIANCE_CLEARED", 2), request);
        assertThat(repository.transition.command()).isEqualTo(MerchantCommand.ENABLE);
        controller.terminate(91, transition("BUSINESS_CLOSED", 3), request);
        assertThat(repository.transition.command()).isEqualTo(MerchantCommand.TERMINATE);

        assertThatThrownBy(() -> controller.disable(91, json.readTree("""
            {"reasonCode":"COMPLIANCE_HOLD","expectedVersion":1,
             "idempotencyKey":"8ce154cf-4f13-4aac-b0de-74922513a14f","status":"DISABLED"}
            """), request)).isInstanceOf(MerchantException.InvalidRequest.class);
    }

    @Test
    void platformProfileUpdateAcceptsOnlyTheExactFrozenBody() throws Exception {
        var controller = new MerchantPlatformController(service,
            new MerchantSubjectAdapter(AccountDomain.PLATFORM));
        HttpServletRequest request = request(subject());

        controller.updateProfile(91, json.readTree("""
            {"expectedVersion":4,
             "idempotencyKey":"8ce154cf-4f13-4aac-b0de-74922513a14f",
             "legalName":"Example Legal","displayName":"Example",
             "merchantTypeCode":"DIRECT","legalPersonName":"Example Owner",
             "authenticationType":"ENTERPRISE","remarks":"note",
             "marketCodes":["PHL","BRA"]}
            """), request);
        assertThat(repository.profileUpdate.marketCodes()).containsExactly("BRA", "PHL");
        assertThat(repository.profileUpdate.merchantTypeCode()).isEqualTo("DIRECT");
        assertThat(repository.profileUpdate.legalPersonName()).isEqualTo("Example Owner");
        assertThat(repository.profileUpdate.authenticationType()).isEqualTo("ENTERPRISE");

        assertThatThrownBy(() -> controller.updateProfile(91, json.readTree("""
            {"expectedVersion":4,
             "idempotencyKey":"8ce154cf-4f13-4aac-b0de-74922513a14f",
             "legalName":"Example Legal","displayName":"Example",
             "merchantTypeCode":"DIRECT","legalPersonName":"Example Owner",
             "authenticationType":"ENTERPRISE","remarks":"note",
             "marketCodes":["BRA"],"tenantId":"8"}
            """), request)).isInstanceOf(MerchantException.InvalidRequest.class);
        assertThatThrownBy(() -> controller.updateProfile(91, json.readTree("""
            {"expectedVersion":4,
             "idempotencyKey":"8ce154cf-4f13-4aac-b0de-74922513a14f",
             "legalName":"Legacy Legal","displayName":"Legacy",
             "remarks":"legacy","marketCodes":["BRA"]}
            """), request)).isInstanceOf(MerchantException.InvalidRequest.class);
        assertThatThrownBy(() -> controller.updateProfile(91, json.readTree("""
            {"expectedVersion":4,
             "idempotencyKey":"8ce154cf-4f13-4aac-b0de-74922513a14f",
             "legalName":"Example Legal","displayName":"Example",
             "merchantTypeCode":"DIRECT","legalPersonName":"Example Owner",
             "authenticationType":"ENTERPRISE","remarks":"note",
             "marketCodes":null}
            """), request)).isInstanceOf(MerchantException.InvalidRequest.class);
        assertThatThrownBy(() -> controller.updateProfile(91, json.readTree("""
            {"expectedVersion":4,
             "idempotencyKey":"8ce154cf-4f13-4aac-b0de-74922513a14f",
             "legalName":"Example Legal","displayName":"Example",
             "merchantTypeCode":null,"legalPersonName":"Example Owner",
             "authenticationType":"ENTERPRISE","remarks":"note","marketCodes":["BRA"]}
            """), request)).isInstanceOf(MerchantException.InvalidRequest.class);
    }

    @Test
    void platformProfileSemanticNoopMapsToFrozenStateConflictResponse() throws Exception {
        repository.rejectProfileAsNoop = true;
        var controller = new MerchantPlatformController(service,
            new MerchantSubjectAdapter(AccountDomain.PLATFORM));
        HttpServletRequest request = request(subject());

        assertThatThrownBy(() -> controller.updateProfile(91, json.readTree("""
            {"expectedVersion":4,
             "idempotencyKey":"8ce154cf-4f13-4aac-b0de-74922513a14f",
             "legalName":"Example Legal","displayName":"Example",
             "merchantTypeCode":"DIRECT","legalPersonName":"Example Owner",
             "authenticationType":"ENTERPRISE","remarks":"note",
             "marketCodes":["BRA"]}
            """), request)).isInstanceOf(MerchantException.StateConflict.class);

        var response = new MerchantHttpExceptionHandler(() -> "trace-test")
            .state(new MerchantException.StateConflict());
        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody())
            .extracting(MerchantApiResponse::code, MerchantApiResponse::error,
                MerchantApiResponse::message, MerchantApiResponse::traceId)
            .containsExactly(40910, "MERCHANT_STATE_CONFLICT",
                "The command is not legal from the current state", "trace-test");
    }

    @Test
    void platformListPassesExactClassificationFiltersToCore() {
        var controller = new MerchantPlatformController(service,
            new MerchantSubjectAdapter(AccountDomain.PLATFORM));

        controller.merchants(java.util.Map.of("merchantTypeCode", "DIRECT",
            "authenticationType", "ENTERPRISE"), request(subject()));

        assertThat(repository.query.merchantTypeCode()).isEqualTo("DIRECT");
        assertThat(repository.query.authenticationType()).isEqualTo("ENTERPRISE");
    }

    @Test
    void responseUsesStringIdsAndNeverContainsRegistrationPlaintextOrProtectionMetadata()
        throws Exception {
        repository.self = detail(true);
        var controller = new MerchantSelfController(service,
            new MerchantSubjectAdapter(AccountDomain.MERCHANT));

        String body = json.writeValueAsString(controller.application(request(subject())));
        var merchant = json.readTree(body).get("data").get("merchant");

        assertThat(body).contains("\"merchantId\":\"720000000000000001\"")
            .contains("\"tenantId\":\"620000000000000001\"")
            .contains("\"registrationNumberMasked\":\"******6789\"")
            .doesNotContain("2026-001234-Z", "ciphertext", "fingerprint", "keyId", "nonce",
                "decidedByMembershipId", "912345678901234567");
        assertThat(merchant.propertyNames()).containsExactlyInAnyOrder(
            "merchantId", "tenantId", "merchantCode", "legalName", "displayName",
            "merchantTypeCode", "legalPersonName", "authenticationType",
            "registrationCountry", "registrationNumberMasked", "remarks",
            "statusReasonCode", "marketCodes", "status", "rowVersion", "submittedAt",
            "reviewedAt", "lastDecision", "createdAt", "updatedAt");
        assertThat(merchant.has("brandName")).isFalse();
        assertThat(merchant.has("contactEmail")).isFalse();
        assertThat(merchant.has("legalIdNoMasked")).isFalse();
    }

    @Test
    void platformDetailUsesTheExactExpandedProfileWithoutProtectionMetadata() throws Exception {
        repository.self = platformDetail();
        var controller = new MerchantPlatformController(service,
            new MerchantSubjectAdapter(AccountDomain.PLATFORM));

        String body = json.writeValueAsString(controller.merchant(
            720000000000000001L, request(subject())));
        var merchant = json.readTree(body).get("data");

        assertThat(merchant.propertyNames()).containsExactlyInAnyOrder(
            "merchantId", "tenantId", "merchantCode", "legalName", "displayName",
            "merchantTypeCode", "legalPersonName", "authenticationType",
            "registrationCountry", "registrationNumberMasked", "remarks",
            "statusReasonCode", "marketCodes", "status", "rowVersion", "submittedAt",
            "reviewedAt", "lastDecision", "createdAt", "updatedAt", "brandName",
            "industryCode", "registeredAddress", "operatingAddress", "contactEmail",
            "contactPhone", "legalIdTypeCode", "legalIdValidity", "legalIdNoMasked",
            "brandLogoDocument", "businessLicenseDocument", "legalIdFrontDocument",
            "legalIdBackDocument", "legalIdHoldingDocument");
        assertThat(merchant.get("legalIdValidity").propertyNames())
            .containsExactlyInAnyOrder("validFrom", "validTo");
        for (String field : java.util.List.of("brandLogoDocument", "businessLicenseDocument",
            "legalIdFrontDocument", "legalIdBackDocument", "legalIdHoldingDocument")) {
            assertThat(merchant.get(field).propertyNames()).containsExactlyInAnyOrder(
                "documentId", "kind", "mediaType", "width", "height", "sizeBytes");
            assertThat(merchant.get(field).get("documentId").isString()).isTrue();
        }
        assertThat(merchant.get("lastDecision").get("decidedByMembershipId").asString())
            .isEqualTo("912345678901234567");
        assertThat(body).doesNotContain("ciphertext", "fingerprint", "keyId", "nonce",
            "2026-001234-Z", "A123456789");
    }

    @Test
    void applicationNonNullSerializationStillEmitsContractualNullableFields() throws Exception {
        ObjectMapper applicationJson = json.rebuild()
            .changeDefaultPropertyInclusion(ignored -> JsonInclude.Value.construct(
                JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL))
            .build();

        String noPendingAmendment = applicationJson.writeValueAsString(
            MerchantApiResponse.success(null, () -> "trace-pending-null"));
        assertThat(noPendingAmendment).contains("\"data\":null");

        var controller = new MerchantSelfController(service,
            new MerchantSubjectAdapter(AccountDomain.MERCHANT));

        String empty = applicationJson.writeValueAsString(controller.application(request(subject())));
        assertThat(empty).contains("\"merchant\":null");

        repository.self = detail(false);
        String pending = applicationJson.writeValueAsString(controller.application(request(subject())));
        assertThat(pending).contains("\"reviewedAt\":null", "\"lastDecision\":null");

        var platform = new MerchantPlatformController(service,
            new MerchantSubjectAdapter(AccountDomain.PLATFORM));
        String page = applicationJson.writeValueAsString(platform.merchants(
            java.util.Map.of("page", "1", "pageSize", "20"), request(subject())));
        assertThat(page).contains("\"statusReasonCode\":null", "\"marketCodes\":[]",
            "\"merchantTypeCode\":null", "\"legalPersonName\":null",
            "\"authenticationType\":null", "\"reviewPending\":true");
    }

    @Test
    void platformAndSelfConfigurationsRegisterOnlyTheirOwnedControllers() {
        assertThat(MerchantPlatformHttpConfiguration.class.getAnnotation(Import.class).value())
            .containsExactlyInAnyOrder(MerchantPlatformController.class,
                MerchantOnboardingController.class, MerchantDocumentController.class,
                MerchantHttpExceptionHandler.class);
        assertThat(MerchantSelfHttpConfiguration.class.getAnnotation(Import.class).value())
            .containsExactlyInAnyOrder(MerchantSelfController.class,
                MerchantHttpExceptionHandler.class);
    }

    @Test
    void documentContentUsesTheOptionalPositiveAmendmentSelectorAndExactSecurityHeaders() {
        var controller = new MerchantDocumentController(
            new com.niv.payment.merchant.core.MerchantOnboardingService(repository),
            new MerchantSubjectAdapter(AccountDomain.PLATFORM), () -> "trace-test");

        var pending = controller.merchantContent(91, "BRAND_LOGO", "72", request(subject()));
        assertThat(repository.documentAmendmentId).isEqualTo(72L);
        assertThat(pending.getBody()).containsExactly((byte) 1, (byte) 2);
        assertThat(pending.getHeaders().getContentType().toString()).isEqualTo("image/png");
        assertThat(pending.getHeaders().getContentLength()).isEqualTo(2);
        assertThat(pending.getHeaders().getFirst("Content-Disposition")).isEqualTo("inline");
        assertThat(pending.getHeaders().getFirst("Cache-Control")).isEqualTo("no-store");
        assertThat(pending.getHeaders().getFirst("Pragma")).isEqualTo("no-cache");
        assertThat(pending.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(pending.getHeaders().getFirst("Content-Security-Policy"))
            .isEqualTo("sandbox; default-src 'none'");

        controller.merchantContent(91, "BRAND_LOGO", null, request(subject()));
        assertThat(repository.documentAmendmentId).isNull();
        assertThatThrownBy(() -> controller.merchantContent(
            91, "BRAND_LOGO", "0", request(subject())))
            .isInstanceOf(MerchantException.InvalidRequest.class);
        assertThatThrownBy(() -> controller.merchantContent(
            91, "BRAND_LOGO", "", request(subject())))
            .isInstanceOf(MerchantException.InvalidRequest.class);
    }

    @Test
    void controllerAnnotationsExposeOnlyTheFrozenEightEndpointInventory() {
        assertThat(MerchantSelfController.class.getAnnotation(RequestMapping.class).value())
            .containsExactly("/api/merchant/application");
        assertThat(mapping(MerchantSelfController.class, "application", GetMapping.class))
            .isEmpty();
        assertThat(mapping(MerchantSelfController.class, "submit", PostMapping.class))
            .containsExactly("/submissions");

        assertThat(MerchantPlatformController.class.getAnnotation(RequestMapping.class).value())
            .containsExactly("/api/platform/merchants");
        assertThat(mapping(MerchantPlatformController.class, "merchants", GetMapping.class))
            .isEmpty();
        assertThat(mapping(MerchantPlatformController.class, "merchant", GetMapping.class))
            .containsExactly("/{merchantId}");
        assertThat(mapping(MerchantPlatformController.class, "updateProfile", PutMapping.class))
            .containsExactly("/{merchantId}/profile");
        assertThat(mapping(MerchantPlatformController.class, "review", PostMapping.class))
            .containsExactly("/{merchantId}/review-decisions");
        assertThat(mapping(MerchantPlatformController.class, "disable", PostMapping.class))
            .containsExactly("/{merchantId}/disable");
        assertThat(mapping(MerchantPlatformController.class, "enable", PostMapping.class))
            .containsExactly("/{merchantId}/enable");
        assertThat(mapping(MerchantPlatformController.class, "terminate", PostMapping.class))
            .containsExactly("/{merchantId}/terminate");
    }

    @Test
    void merchantExceptionsMapToFrozenCodesAndBoundedMessages() {
        var handler = new MerchantHttpExceptionHandler(() -> "trace-test");

        assertThat(handler.invalid(new MerchantException.InvalidRequest("secret 2026-001234-Z"))
            .getBody()).extracting(MerchantApiResponse::code, MerchantApiResponse::error,
            MerchantApiResponse::message, MerchantApiResponse::traceId)
            .containsExactly(40001, "INVALID_REQUEST", "Invalid request", "trace-test");
        assertThat(handler.permission(new MerchantException.PermissionDenied()).getBody().code())
            .isEqualTo(40301);
        assertThat(handler.notFound(new MerchantException.ResourceNotFound()).getBody().code())
            .isEqualTo(40401);
        assertThat(handler.dataConflict(new MerchantException.DataConflict()).getBody().code())
            .isEqualTo(40901);
        assertThat(handler.optimistic(new MerchantException.OptimisticLockConflict()).getBody().code())
            .isEqualTo(40902);
        assertThat(handler.state(new MerchantException.StateConflict()).getBody().code())
            .isEqualTo(40910);
        assertThat(handler.idempotency(new MerchantException.IdempotencyConflict()).getBody().code())
            .isEqualTo(40911);
        assertThat(handler.protection(new MerchantException.ProtectedFieldUnavailable()).getBody().code())
            .isEqualTo(50301);
    }

    private tools.jackson.databind.JsonNode transition(String reason, long version) throws Exception {
        return json.readTree("{\"reasonCode\":\"" + reason + "\",\"expectedVersion\":" + version
            + ",\"idempotencyKey\":\"" + KEY + "\"}");
    }

    private static <A extends java.lang.annotation.Annotation> String[] mapping(
        Class<?> type, String methodName, Class<A> annotationType) {
        return java.util.Arrays.stream(type.getDeclaredMethods())
            .filter(method -> method.getName().equals(methodName))
            .findFirst().orElseThrow().getAnnotation(annotationType) instanceof GetMapping get
                ? get.value()
                : java.util.Arrays.stream(type.getDeclaredMethods())
                    .filter(method -> method.getName().equals(methodName))
                    .findFirst().orElseThrow().getAnnotation(annotationType) instanceof PostMapping post
                    ? post.value()
                    : java.util.Arrays.stream(type.getDeclaredMethods())
                        .filter(method -> method.getName().equals(methodName))
                        .findFirst().orElseThrow().getAnnotation(annotationType) instanceof PutMapping put
                        ? put.value() : throwMissingMapping();
    }

    private static String[] throwMissingMapping() {
        throw new AssertionError("Expected endpoint mapping annotation");
    }

    private static AuthorizationSubject subject() {
        return new AuthorizationSubject(11, 12, 13, null, 14, 15,
            16, "https://issuer.example", "subject-17", true, false);
    }

    private static HttpServletRequest request(AuthorizationSubject subject) {
        return (HttpServletRequest) Proxy.newProxyInstance(
            MerchantHttpContractTest.class.getClassLoader(),
            new Class<?>[] {HttpServletRequest.class},
            (proxy, method, args) -> {
                if (method.getName().equals("getAttribute")
                    && AuthorizationSubject.class.getName().equals(args[0])) return subject;
                if (method.getReturnType() == boolean.class) return false;
                if (method.getReturnType() == int.class) return 0;
                if (method.getReturnType() == long.class) return 0L;
                return null;
            });
    }

    private static MerchantDetail detail(boolean decision) {
        OffsetDateTime time = OffsetDateTime.parse("2026-08-13T08:00:00Z");
        return new MerchantDetail(720000000000000001L, 620000000000000001L,
            "MCH_EXAMPLE_ALPHA", "Example Legal", "Example", "SG", "******6789",
            MerchantStatus.PENDING_REVIEW, 0, time, null,
            decision ? new com.niv.payment.merchant.core.MerchantModels.LastDecision(
                "REJECT", "PROFILE_MISMATCH", time, 912345678901234567L) : null, time, time);
    }

    private static MerchantDetail platformDetail() {
        OffsetDateTime time = OffsetDateTime.parse("2026-08-13T08:00:00Z");
        var validity = new com.niv.payment.merchant.core.MerchantOnboardingModels.LegalIdValidity(
            java.time.LocalDate.parse("2020-01-01"), java.time.LocalDate.parse("2030-01-01"));
        var documents = java.util.Arrays.stream(
                com.niv.payment.merchant.core.MerchantOnboardingModels.DocumentKind.values())
            .map(kind -> new com.niv.payment.merchant.core.MerchantOnboardingModels.DocumentMetadata(
                730000000000000001L + kind.ordinal(), 620000000000000001L, kind,
                "image/png", 320, 240, 12345, time.plusMinutes(30), true))
            .toList();
        return new MerchantDetail(720000000000000001L, 620000000000000001L,
            "MCH_EXAMPLE_ALPHA", "Example Legal", "Example", "PLATFORM", "Example Owner",
            "ENTERPRISE", "BRA", "******6789", "note", null, java.util.List.of("BRA", "PHL"),
            MerchantStatus.ACTIVE, 2, time, time,
            new com.niv.payment.merchant.core.MerchantModels.LastDecision(
                "APPROVE", "PROFILE_VERIFIED", time, 912345678901234567L), time, time,
            "Example Brand", "ECOMMERCE", "Registered Address", "Operating Address",
            "owner@example.test", "+5511999999998", "NATIONAL_ID", validity, "******6789",
            documents.get(0), documents.get(1), documents.get(2), documents.get(3), documents.get(4));
    }

    private static final class RecordingRepository implements MerchantRepository {
        SubmissionCommand submission;
        TransitionCommand transition;
        ProfileUpdateCommand profileUpdate;
        MerchantQuery query;
        MerchantDetail self;
        Long documentAmendmentId;
        boolean rejectProfileAsNoop;

        @Override
        public MerchantMutationResult submit(MerchantActor actor, SubmissionCommand command) {
            this.submission = command;
            return new MerchantMutationResult(720000000000000001L, "MCH_EXAMPLE_ALPHA",
                MerchantStatus.PENDING_REVIEW, 0);
        }

        @Override
        public Optional<MerchantDetail> findSelf(MerchantActor actor) {
            return Optional.ofNullable(self);
        }

        @Override
        public MerchantPage findPlatform(MerchantActor actor, MerchantQuery query) {
            this.query = query;
            return new MerchantPage(self == null ? java.util.List.of() : java.util.List.of(self),
                self == null ? 0 : 1,
                self == null ? java.util.Set.of() : java.util.Set.of(self.merchantId()));
        }

        @Override
        public MerchantDetail findPlatformDetail(MerchantActor actor, long merchantId) {
            if (self == null) throw new MerchantException.ResourceNotFound();
            return self;
        }

        @Override
        public MerchantMutationResult transition(MerchantActor actor, TransitionCommand command) {
            this.transition = command;
            return new MerchantMutationResult(command.merchantId(), "MCH_EXAMPLE_ALPHA",
                MerchantStatus.ACTIVE, command.expectedVersion() + 1);
        }

        @Override
        public MerchantMutationResult updateProfile(MerchantActor actor, ProfileUpdateCommand command) {
            this.profileUpdate = command;
            if (command.merchantTypeCode() == null) {
                throw new MerchantException.InvalidRequest("Legacy profile body is replay-only");
            }
            if (rejectProfileAsNoop) throw new MerchantException.StateConflict();
            return new MerchantMutationResult(command.merchantId(), "MCH_EXAMPLE_ALPHA",
                MerchantStatus.ACTIVE, command.expectedVersion() + 1);
        }

        @Override
        public com.niv.payment.merchant.core.MerchantOnboardingModels.DocumentContent readMerchantDocument(
            MerchantActor actor, long merchantId,
            com.niv.payment.merchant.core.MerchantOnboardingModels.DocumentKind kind,
            Long amendmentId
        ) {
            documentAmendmentId = amendmentId;
            return new com.niv.payment.merchant.core.MerchantOnboardingModels.DocumentContent(
                "image/png", new byte[]{1, 2});
        }
    }
}
