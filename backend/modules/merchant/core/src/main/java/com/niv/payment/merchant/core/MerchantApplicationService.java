package com.niv.payment.merchant.core;

import java.util.Objects;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class MerchantApplicationService {
    private static final Set<String> OPERATING_MARKETS = Set.of("BRA", "PHL");
    private static final Set<String> MERCHANT_TYPES = Set.of(
        "DIRECT", "INDIRECT", "COMMISSION", "SALES", "PLATFORM");
    private static final Set<String> AUTHENTICATION_TYPES = Set.of(
        "ENTERPRISE", "NON_PROFIT_ORGANIZATIONS", "CLIQUE", "INDIVIDUAL",
        "INDIVIDUAL_HOUSEHOLD");
    private final MerchantRepository repository;

    public MerchantApplicationService(MerchantRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    public MerchantMutationResult submit(MerchantActor actor, SubmissionRequest request) {
        Objects.requireNonNull(actor, "actor");
        if (request == null || request.idempotencyKey() == null || !request.expectedVersionPresent()) {
            throw new MerchantException.InvalidRequest("Required submission fields are missing");
        }
        MerchantCommand command = request.expectedVersion() == null
            ? MerchantCommand.SUBMIT : MerchantCommand.RESUBMIT;
        if (request.expectedVersion() != null && request.expectedVersion() < 0) {
            throw new MerchantException.InvalidRequest("expectedVersion cannot be negative");
        }
        String legalName = boundedText(request.legalName(), 200, "legalName");
        String displayName = boundedText(request.displayName(), 128, "displayName");
        String country = RegistrationNumberPolicy.country(request.registrationCountry());
        String number = request.registrationNumber() == null ? null
            : RegistrationNumberPolicy.normalize(request.registrationNumber());
        if (command == MerchantCommand.SUBMIT && number == null) {
            throw new MerchantException.InvalidRequest("registrationNumber is required on submission");
        }
        return repository.submit(actor, new SubmissionCommand(command, request.idempotencyKey(),
            request.expectedVersion(), legalName, displayName, country, number));
    }

    public java.util.Optional<MerchantDetail> findSelf(MerchantActor actor) {
        return repository.findSelf(Objects.requireNonNull(actor, "actor"));
    }

    public MerchantPage findPlatform(MerchantActor actor, MerchantQuery query) {
        Objects.requireNonNull(actor, "actor");
        if (query == null || query.page() < 1 || query.pageSize() < 1 || query.pageSize() > 100
            || (query.createdFrom() != null && query.createdTo() != null
                && !query.createdTo().isAfter(query.createdFrom()))) {
            throw new MerchantException.InvalidRequest("Invalid merchant query");
        }
        if (query.merchantCode() != null && (query.merchantCode().isBlank()
            || query.merchantCode().length() > 64 || !query.merchantCode().equals(query.merchantCode().trim()))) {
            throw new MerchantException.InvalidRequest("Invalid merchantCode filter");
        }
        if (query.name() != null) boundedText(query.name(), 200, "name");
        if (query.registrationCountry() != null) RegistrationNumberPolicy.country(query.registrationCountry());
        if (query.marketCode() != null && (!query.marketCode().matches("^[A-Z]{3}$")
            || !OPERATING_MARKETS.contains(query.marketCode()))) {
            throw new MerchantException.InvalidRequest("Invalid marketCode filter");
        }
        if (query.merchantTypeCode() != null
            && !MERCHANT_TYPES.contains(query.merchantTypeCode())) {
            throw new MerchantException.InvalidRequest("Invalid merchantTypeCode filter");
        }
        if (query.authenticationType() != null
            && !AUTHENTICATION_TYPES.contains(query.authenticationType())) {
            throw new MerchantException.InvalidRequest("Invalid authenticationType filter");
        }
        return repository.findPlatform(actor, query);
    }

    public MerchantDetail findPlatformDetail(MerchantActor actor, long merchantId) {
        if (merchantId <= 0) throw new MerchantException.InvalidRequest("merchantId must be positive");
        return repository.findPlatformDetail(Objects.requireNonNull(actor, "actor"), merchantId);
    }

    public MerchantMutationResult transition(MerchantActor actor, TransitionCommand command) {
        return repository.transition(Objects.requireNonNull(actor, "actor"),
            Objects.requireNonNull(command, "command"));
    }

    public MerchantMutationResult updateProfile(MerchantActor actor, long merchantId,
                                                ProfileUpdateRequest request) {
        Objects.requireNonNull(actor, "actor");
        if (merchantId <= 0 || request == null || request.idempotencyKey() == null
            || request.expectedVersion() < 0 || request.marketCodes() == null
            || request.marketCodes().isEmpty() || request.marketCodes().size() > 20) {
            throw new MerchantException.InvalidRequest("Invalid Merchant profile update");
        }
        String legalName = boundedText(request.legalName(), 200, "legalName");
        String displayName = boundedText(request.displayName(), 128, "displayName");
        boolean legacyReplay = request.merchantTypeCode() == null
            && request.legalPersonName() == null && request.authenticationType() == null;
        if (!legacyReplay && (request.merchantTypeCode() == null
            || request.legalPersonName() == null || request.authenticationType() == null)) {
            throw new MerchantException.InvalidRequest("Incomplete Merchant classification");
        }
        String merchantTypeCode = legacyReplay ? null : exactValue(
            request.merchantTypeCode(), MERCHANT_TYPES, "merchantTypeCode");
        String legalPersonName = legacyReplay ? null
            : boundedText(request.legalPersonName(), 200, "legalPersonName");
        String authenticationType = legacyReplay ? null : exactValue(
            request.authenticationType(), AUTHENTICATION_TYPES, "authenticationType");
        String remarks = boundedOptionalText(request.remarks(), 300, "remarks");
        HashSet<String> unique = new HashSet<>();
        for (String marketCode : request.marketCodes()) {
            if (marketCode == null || !marketCode.matches("^[A-Z]{3}$")
                || !OPERATING_MARKETS.contains(marketCode) || !unique.add(marketCode)) {
                throw new MerchantException.InvalidRequest("Invalid marketCodes");
            }
        }
        List<String> markets = unique.stream().sorted().toList();
        return repository.updateProfile(actor, new ProfileUpdateCommand(merchantId,
            request.expectedVersion(), request.idempotencyKey(), legalName, displayName,
            merchantTypeCode, legalPersonName, authenticationType, remarks, markets));
    }

    private static String exactValue(String value, Set<String> allowed, String field) {
        if (value == null || !allowed.contains(value)) {
            throw new MerchantException.InvalidRequest("Invalid " + field);
        }
        return value;
    }

    private static String boundedText(String value, int maximum, String field) {
        if (value == null) throw new MerchantException.InvalidRequest(field + " is required");
        String result = trimWhitespace(value);
        int length = result.codePointCount(0, result.length());
        if (length < 1 || length > maximum) {
            throw new MerchantException.InvalidRequest(field + " has invalid length");
        }
        return result;
    }

    private static String boundedOptionalText(String value, int maximum, String field) {
        if (value == null) throw new MerchantException.InvalidRequest(field + " is required");
        String result = trimWhitespace(value);
        if (result.codePointCount(0, result.length()) > maximum) {
            throw new MerchantException.InvalidRequest(field + " has invalid length");
        }
        return result;
    }

    private static String trimWhitespace(String value) {
        int start = 0;
        int end = value.length();
        while (start < end) {
            int codePoint = value.codePointAt(start);
            if (!Character.isWhitespace(codePoint) && !Character.isSpaceChar(codePoint)) break;
            start += Character.charCount(codePoint);
        }
        while (end > start) {
            int codePoint = value.codePointBefore(end);
            if (!Character.isWhitespace(codePoint) && !Character.isSpaceChar(codePoint)) break;
            end -= Character.charCount(codePoint);
        }
        return value.substring(start, end);
    }
}
