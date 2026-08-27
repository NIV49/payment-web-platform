package com.niv.payment.merchant.web;

import com.niv.payment.merchant.core.MerchantApplicationService;
import com.niv.payment.merchant.core.MerchantCommand;
import com.niv.payment.merchant.core.MerchantException;
import com.niv.payment.merchant.core.MerchantQuery;
import com.niv.payment.merchant.core.MerchantStatus;
import com.niv.payment.merchant.core.ProfileUpdateRequest;
import com.niv.payment.merchant.core.TransitionCommand;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/platform/merchants")
final class MerchantPlatformController {
    private static final Set<String> QUERY_FIELDS = Set.of("merchantCode", "name", "status",
        "registrationCountry", "marketCode", "merchantTypeCode", "authenticationType",
        "createdFrom", "createdTo", "page", "pageSize");
    private static final Set<String> REVIEW_FIELDS = Set.of("decision", "reasonCode",
        "expectedVersion", "idempotencyKey");
    private static final Set<String> TRANSITION_FIELDS = Set.of("reasonCode", "expectedVersion",
        "idempotencyKey");
    private static final Set<String> LEGACY_PROFILE_FIELDS = Set.of("expectedVersion", "idempotencyKey",
        "legalName", "displayName", "remarks", "marketCodes");
    private static final Set<String> PROFILE_FIELDS = Set.of("expectedVersion", "idempotencyKey",
        "legalName", "displayName", "merchantTypeCode", "legalPersonName",
        "authenticationType", "remarks", "marketCodes");
    private final MerchantApplicationService merchants;
    private final MerchantSubjectAdapter subjects;
    private final MerchantRequestTrace trace;

    @Autowired
    MerchantPlatformController(MerchantApplicationService merchants, MerchantSubjectAdapter subjects,
                               MerchantRequestTrace trace) {
        this.merchants = merchants;
        this.subjects = subjects;
        this.trace = trace;
    }

    MerchantPlatformController(MerchantApplicationService merchants, MerchantSubjectAdapter subjects) {
        this(merchants, subjects, () -> "test-trace");
    }

    @GetMapping
    MerchantApiResponse<MerchantResponses.Page> merchants(
        @RequestParam Map<String, String> query, HttpServletRequest request) {
        if (!QUERY_FIELDS.containsAll(query.keySet())) throw MerchantJson.invalid();
        MerchantQuery criteria = new MerchantQuery(query.get("merchantCode"), query.get("name"),
            status(query.get("status")), query.get("registrationCountry"),
            query.get("marketCode"), query.get("merchantTypeCode"),
            query.get("authenticationType"),
            instant(query.get("createdFrom")), instant(query.get("createdTo")),
            integer(query.get("page"), 1), integer(query.get("pageSize"), 20));
        return MerchantApiResponse.success(
            MerchantResponses.page(merchants.findPlatform(subjects.actor(request), criteria)), trace);
    }

    @PutMapping("/{merchantId}/profile")
    MerchantApiResponse<MerchantResponses.Mutation> updateProfile(
        @PathVariable long merchantId, @RequestBody JsonNode body, HttpServletRequest request) {
        boolean legacyReplay = body != null && body.isObject()
            && LEGACY_PROFILE_FIELDS.containsAll(body.propertyNames())
            && !body.has("merchantTypeCode") && !body.has("legalPersonName")
            && !body.has("authenticationType");
        MerchantJson.exactObject(body, legacyReplay ? LEGACY_PROFILE_FIELDS : PROFILE_FIELDS);
        ProfileUpdateRequest update = new ProfileUpdateRequest(
            MerchantJson.nonNegativeLong(body, "expectedVersion"),
            MerchantJson.uuid(body, "idempotencyKey"),
            MerchantJson.requiredString(body, "legalName"),
            MerchantJson.requiredString(body, "displayName"),
            legacyReplay ? null : MerchantJson.requiredString(body, "merchantTypeCode"),
            legacyReplay ? null : MerchantJson.requiredString(body, "legalPersonName"),
            legacyReplay ? null : MerchantJson.requiredString(body, "authenticationType"),
            MerchantJson.requiredString(body, "remarks"),
            MerchantJson.requiredStringList(body, "marketCodes"));
        return MerchantApiResponse.success(MerchantResponses.mutation(
            merchants.updateProfile(subjects.actor(request), merchantId, update)), trace);
    }

    @GetMapping("/{merchantId}")
    MerchantApiResponse<MerchantResponses.PlatformDetail> merchant(@PathVariable long merchantId,
                                                            HttpServletRequest request) {
        return MerchantApiResponse.success(MerchantResponses.platformDetail(
            merchants.findPlatformDetail(subjects.actor(request), merchantId)), trace);
    }

    @PostMapping("/{merchantId}/review-decisions")
    MerchantApiResponse<MerchantResponses.Mutation> review(
        @PathVariable long merchantId, @RequestBody JsonNode body, HttpServletRequest request) {
        MerchantJson.exactObject(body, REVIEW_FIELDS);
        MerchantCommand command;
        String decision = MerchantJson.requiredString(body, "decision");
        if (decision.equals("APPROVE")) command = MerchantCommand.APPROVE;
        else if (decision.equals("REJECT")) command = MerchantCommand.REJECT;
        else throw MerchantJson.invalid();
        return transition(merchantId, command, body, request);
    }

    @PostMapping("/{merchantId}/disable")
    MerchantApiResponse<MerchantResponses.Mutation> disable(
        @PathVariable long merchantId, @RequestBody JsonNode body, HttpServletRequest request) {
        return transitionExact(merchantId, MerchantCommand.DISABLE, body, request);
    }

    @PostMapping("/{merchantId}/enable")
    MerchantApiResponse<MerchantResponses.Mutation> enable(
        @PathVariable long merchantId, @RequestBody JsonNode body, HttpServletRequest request) {
        return transitionExact(merchantId, MerchantCommand.ENABLE, body, request);
    }

    @PostMapping("/{merchantId}/terminate")
    MerchantApiResponse<MerchantResponses.Mutation> terminate(
        @PathVariable long merchantId, @RequestBody JsonNode body, HttpServletRequest request) {
        return transitionExact(merchantId, MerchantCommand.TERMINATE, body, request);
    }

    private MerchantApiResponse<MerchantResponses.Mutation> transitionExact(
        long merchantId, MerchantCommand command, JsonNode body, HttpServletRequest request) {
        MerchantJson.exactObject(body, TRANSITION_FIELDS);
        return transition(merchantId, command, body, request);
    }

    private MerchantApiResponse<MerchantResponses.Mutation> transition(
        long merchantId, MerchantCommand command, JsonNode body, HttpServletRequest request) {
        TransitionCommand transition = new TransitionCommand(merchantId, command,
            MerchantJson.requiredString(body, "reasonCode"),
            MerchantJson.nonNegativeLong(body, "expectedVersion"),
            MerchantJson.uuid(body, "idempotencyKey"));
        return MerchantApiResponse.success(MerchantResponses.mutation(
            merchants.transition(subjects.actor(request), transition)), trace);
    }

    private static MerchantStatus status(String value) {
        if (value == null) return null;
        try {
            return MerchantStatus.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw MerchantJson.invalid();
        }
    }

    private static OffsetDateTime instant(String value) {
        if (value == null) return null;
        try {
            OffsetDateTime parsed = OffsetDateTime.parse(value);
            if (!parsed.getOffset().equals(ZoneOffset.UTC)) {
                throw MerchantJson.invalid();
            }
            return OffsetDateTime.ofInstant(Instant.from(parsed), ZoneOffset.UTC);
        } catch (DateTimeParseException exception) {
            throw MerchantJson.invalid();
        }
    }

    private static int integer(String value, int fallback) {
        if (value == null) return fallback;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw MerchantJson.invalid();
        }
    }
}
