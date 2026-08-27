package com.niv.payment.merchant.core;

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
import com.niv.payment.merchant.core.MerchantOnboardingModels.Profile;
import com.niv.payment.merchant.core.MerchantOnboardingModels.SensitiveMode;
import com.niv.payment.merchant.core.MerchantOnboardingModels.SensitiveValue;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public final class MerchantOnboardingService {
    private static final Set<String> MARKETS = Set.of("BRA", "PHL");
    private static final Set<String> MERCHANT_TYPES = Set.of(
        "PLATFORM", "INDIRECT", "COMMISSION", "SALES");
    private static final Set<String> AUTHENTICATION_TYPES = Set.of(
        "ENTERPRISE", "NON_PROFIT_ORGANIZATIONS", "CLIQUE", "INDIVIDUAL",
        "INDIVIDUAL_HOUSEHOLD");
    private static final Set<String> INDUSTRIES = Set.of(
        "FINANCIAL_SERVICES", "ECOMMERCE", "RETAIL", "TRAVEL", "EDUCATION", "OTHER");
    private static final Set<String> LEGAL_ID_TYPES = Set.of(
        "NATIONAL_ID", "PASSPORT", "DRIVER_LICENSE");

    private final MerchantRepository repository;

    public MerchantOnboardingService(MerchantRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    public EligibleTenantPage eligibleTenants(MerchantActor actor, EligibleTenantQuery query) {
        requirePlatform(actor);
        if (query == null || query.page() < 1 || query.pageSize() < 1 || query.pageSize() > 100) {
            throw new MerchantException.InvalidRequest("Invalid eligible Tenant query");
        }
        return repository.findEligibleTenants(actor, new EligibleTenantQuery(
            optionalFilter(query.tenantCode(), 64, "tenantCode"),
            optionalFilter(query.tenantName(), 128, "tenantName"), query.page(), query.pageSize()));
    }

    public DocumentMetadata uploadDocument(MerchantActor actor, DocumentUploadRequest request) {
        requirePlatform(actor);
        if (request == null || request.targetTenantId() <= 0 || request.kind() == null
            || request.normalizedContent() == null || request.normalizedContent().length < 1
            || request.normalizedContent().length > 2 * 1024 * 1024
            || request.width() < 1 || request.height() < 1
            || request.width() > 4096 || request.height() > 4096
            || (long) request.width() * request.height() > 12_000_000L
            || !("image/png".equals(request.mediaType()) || "image/jpeg".equals(request.mediaType()))) {
            throw new MerchantException.InvalidRequest("Invalid Merchant document upload");
        }
        return repository.uploadDocument(actor, new DocumentUploadRequest(
            request.targetTenantId(), request.kind(), request.mediaType(),
            request.normalizedContent(), request.width(), request.height()));
    }

    public DocumentContent stagedDocumentContent(MerchantActor actor, long documentId) {
        return repository.readStagedDocument(requirePlatform(actor), positive(documentId, "documentId"));
    }

    public void deleteDocument(MerchantActor actor, long documentId) {
        repository.deleteDocument(requirePlatform(actor), positive(documentId, "documentId"));
    }

    public DocumentContent merchantDocumentContent(
        MerchantActor actor, long merchantId, DocumentKind kind, Long amendmentId
    ) {
        if (kind == null) throw new MerchantException.InvalidRequest("kind is required");
        if (amendmentId != null && amendmentId <= 0) {
            throw new MerchantException.InvalidRequest("amendmentId must be positive");
        }
        return repository.readMerchantDocument(requirePlatform(actor),
            positive(merchantId, "merchantId"), kind, amendmentId);
    }

    public MerchantMutationResult create(MerchantActor actor, CreateRequest request) {
        requirePlatform(actor);
        if (request == null || request.idempotencyKey() == null || request.targetTenantId() <= 0) {
            throw new MerchantException.InvalidRequest("Invalid Merchant create request");
        }
        return repository.create(actor, new CreateRequest(request.targetTenantId(),
            request.idempotencyKey(), profile(request.profile(), true)));
    }

    public AmendmentResult createAmendment(MerchantActor actor, AmendmentRequest request) {
        requirePlatform(actor);
        if (request == null || request.idempotencyKey() == null || request.merchantId() <= 0
            || request.expectedVersion() < 0) {
            throw new MerchantException.InvalidRequest("Invalid Merchant amendment request");
        }
        return repository.createAmendment(actor, new AmendmentRequest(request.merchantId(),
            request.expectedVersion(), request.idempotencyKey(), profile(request.profile(), false)));
    }

    public Amendment pendingAmendment(MerchantActor actor, long merchantId) {
        return repository.findPendingAmendment(requirePlatform(actor), positive(merchantId, "merchantId"));
    }

    public AmendmentResult reviewAmendment(MerchantActor actor, AmendmentReviewRequest request) {
        requirePlatform(actor);
        if (request == null || request.idempotencyKey() == null || request.decision() == null
            || request.expectedVersion() < 0 || request.merchantId() <= 0
            || request.amendmentId() <= 0 || request.reasonCode() == null
            || (request.decision() == MerchantOnboardingModels.AmendmentDecision.APPROVE
                && !"PROFILE_AMENDMENT_VERIFIED".equals(request.reasonCode()))
            || (request.decision() == MerchantOnboardingModels.AmendmentDecision.REJECT
                && !Set.of("PROFILE_AMENDMENT_MISMATCH", "DOCUMENT_UNVERIFIED",
                    "COMPLIANCE_REJECTED").contains(request.reasonCode()))) {
            throw new MerchantException.InvalidRequest("Invalid amendment review request");
        }
        return repository.reviewAmendment(actor, request);
    }

    private static Profile profile(Profile source, boolean create) {
        if (source == null || source.legalIdValidity() == null
            || source.legalIdValidity().validFrom() == null
            || source.legalIdValidity().validTo() == null
            || source.legalIdValidity().validTo().isBefore(source.legalIdValidity().validFrom())) {
            throw new MerchantException.InvalidRequest("Invalid legalIdValidity");
        }
        List<String> markets = markets(source.marketCodes());
        SensitiveValue legalId = sensitive(source.legalIdNo(), create, "legalIdNo");
        SensitiveValue registration = sensitive(
            source.registrationNumber(), create, "registrationNumber");
        String type = exact(source.merchantTypeCode(), MERCHANT_TYPES, "merchantTypeCode");
        String authentication = exact(
            source.authenticationType(), AUTHENTICATION_TYPES, "authenticationType");
        return new Profile(
            text(source.displayName(), 128, "displayName"),
            text(source.brandName(), 128, "brandName"), authentication, type,
            exact(source.industryCode(), INDUSTRIES, "industryCode"),
            requiredDocument(source.brandLogoDocumentId()),
            text(source.legalName(), 200, "legalName"),
            RegistrationNumberPolicy.country(source.registrationCountry()), markets,
            text(source.registeredAddress(), 300, "registeredAddress"),
            text(source.operatingAddress(), 300, "operatingAddress"),
            requiredDocument(source.businessLicenseDocumentId()),
            text(source.legalPersonName(), 200, "legalPersonName"),
            email(source.contactEmail()), phone(source.contactPhone()),
            exact(source.legalIdTypeCode(), LEGAL_ID_TYPES, "legalIdTypeCode"), legalId,
            source.legalIdValidity(), requiredDocument(source.legalIdFrontDocumentId()),
            requiredDocument(source.legalIdBackDocumentId()),
            requiredDocument(source.legalIdHoldingDocumentId()),
            remarks(source.remarks()), registration);
    }

    private static SensitiveValue sensitive(SensitiveValue value, boolean create, String field) {
        if (value == null || value.mode() == null || (create && value.mode() != SensitiveMode.REPLACE)) {
            throw new MerchantException.InvalidRequest(field + " must use REPLACE");
        }
        if (value.mode() == SensitiveMode.RETAIN) {
            if (value.value() != null) throw new MerchantException.InvalidRequest(field + " RETAIN has no value");
            return value;
        }
        String canonical = RegistrationNumberPolicy.normalize(text(value.value(), 128, field));
        return new SensitiveValue(SensitiveMode.REPLACE, canonical);
    }

    private static List<String> markets(List<String> source) {
        if (source == null || source.isEmpty() || source.size() > MARKETS.size()) {
            throw new MerchantException.InvalidRequest("Invalid marketCodes");
        }
        var unique = new HashSet<String>();
        for (String value : source) {
            if (!MARKETS.contains(value) || !unique.add(value)) {
                throw new MerchantException.InvalidRequest("Invalid marketCodes");
            }
        }
        return unique.stream().sorted().toList();
    }

    private static String email(String value) {
        String normalized = text(value, 254, "contactEmail").toLowerCase(Locale.ROOT);
        if (normalized.codePointCount(0, normalized.length()) < 3
            || normalized.codePoints().anyMatch(Character::isWhitespace)
            || normalized.codePoints().anyMatch(Character::isISOControl)) {
            throw new MerchantException.InvalidRequest("Invalid contactEmail");
        }
        int at = normalized.indexOf('@');
        if (at < 1 || at != normalized.lastIndexOf('@') || at == normalized.length() - 1) {
            throw new MerchantException.InvalidRequest("Invalid contactEmail");
        }
        String local = normalized.substring(0, at);
        String domain = normalized.substring(at + 1);
        if (local.startsWith(".") || local.endsWith(".") || local.contains("..")
            || !local.matches("[a-z0-9.!#$%&'*+/=?^_`{|}~-]+")
            || domain.startsWith(".") || domain.endsWith(".") || domain.contains("..")
            || !domain.contains(".") || !domain.matches("[a-z0-9.-]+")) {
            throw new MerchantException.InvalidRequest("Invalid contactEmail");
        }
        return normalized;
    }

    private static String phone(String value) {
        String normalized = text(value, 16, "contactPhone");
        if (!normalized.matches("^\\+[1-9][0-9]{1,14}$")) {
            throw new MerchantException.InvalidRequest("Invalid contactPhone");
        }
        return normalized;
    }

    private static String code(String value, int maximum, String field) {
        String normalized = text(value, maximum, field);
        if (!normalized.matches("^[A-Z][A-Z0-9_]{0," + (maximum - 1) + "}$")) {
            throw new MerchantException.InvalidRequest("Invalid " + field);
        }
        return normalized;
    }

    private static String exact(String value, Set<String> allowed, String field) {
        if (value == null || !allowed.contains(value)) {
            throw new MerchantException.InvalidRequest("Invalid " + field);
        }
        return value;
    }

    private static String optionalText(String value, int maximum, String field) {
        if (value == null) return "";
        String normalized = trim(value);
        if (normalized.codePointCount(0, normalized.length()) > maximum) {
            throw new MerchantException.InvalidRequest("Invalid " + field);
        }
        return normalized;
    }

    private static String remarks(String value) {
        if (value == null) throw new MerchantException.InvalidRequest("remarks is required");
        return optionalText(value, 300, "remarks");
    }

    private static String optionalFilter(String value, int maximum, String field) {
        if (value == null) return null;
        return text(value, maximum, field);
    }

    private static String text(String value, int maximum, String field) {
        String normalized = trim(value);
        if (normalized == null || normalized.isEmpty()
            || normalized.codePointCount(0, normalized.length()) > maximum) {
            throw new MerchantException.InvalidRequest("Invalid " + field);
        }
        return normalized;
    }

    private static String trim(String value) {
        return value == null ? null : value.strip();
    }

    private static Long requiredDocument(Long value) {
        if (value == null) throw new MerchantException.InvalidRequest("documentId is required");
        if (value <= 0) throw new MerchantException.InvalidRequest("Invalid documentId");
        return value;
    }

    private static long positive(long value, String field) {
        if (value <= 0) throw new MerchantException.InvalidRequest(field + " must be positive");
        return value;
    }

    private static MerchantActor requirePlatform(MerchantActor actor) {
        if (actor == null || actor.accountDomain() != AccountDomain.PLATFORM) {
            throw new MerchantException.PermissionDenied();
        }
        return actor;
    }
}
