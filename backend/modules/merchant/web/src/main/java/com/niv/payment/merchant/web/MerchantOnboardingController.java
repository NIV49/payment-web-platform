package com.niv.payment.merchant.web;

import com.niv.payment.merchant.core.MerchantOnboardingModels.AmendmentDecision;
import com.niv.payment.merchant.core.MerchantOnboardingModels.AmendmentRequest;
import com.niv.payment.merchant.core.MerchantOnboardingModels.AmendmentReviewRequest;
import com.niv.payment.merchant.core.MerchantOnboardingModels.CreateRequest;
import com.niv.payment.merchant.core.MerchantOnboardingModels.EligibleTenantQuery;
import com.niv.payment.merchant.core.MerchantOnboardingService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/platform")
final class MerchantOnboardingController {
    private static final Set<String> ELIGIBLE_QUERY = Set.of(
        "tenantCode","tenantName","page","pageSize");
    private static final Set<String> CREATE_FIELDS = Set.of(
        "idempotencyKey","targetTenantId","profile");
    private static final Set<String> AMENDMENT_FIELDS = Set.of(
        "expectedMerchantVersion","idempotencyKey","profile");
    private static final Set<String> REVIEW_FIELDS = Set.of(
        "decision","reasonCode","expectedVersion","idempotencyKey");

    private final MerchantOnboardingService service;
    private final MerchantSubjectAdapter subjects;
    private final MerchantRequestTrace trace;

    MerchantOnboardingController(MerchantOnboardingService service, MerchantSubjectAdapter subjects,
                                 MerchantRequestTrace trace) {
        this.service = service;
        this.subjects = subjects;
        this.trace = trace;
    }

    @GetMapping("/merchant-onboarding/eligible-tenants")
    MerchantApiResponse<MerchantOnboardingResponses.EligiblePage> eligibleTenants(
        @RequestParam Map<String,String> query, HttpServletRequest request
    ) {
        if (!ELIGIBLE_QUERY.containsAll(query.keySet())) throw MerchantJson.invalid();
        EligibleTenantQuery criteria = new EligibleTenantQuery(query.get("tenantCode"),
            query.get("tenantName"), integer(query.get("page"), 1),
            integer(query.get("pageSize"), 20));
        return MerchantApiResponse.success(MerchantOnboardingResponses.eligible(
            service.eligibleTenants(subjects.actor(request), criteria)), trace);
    }

    @PostMapping("/merchants")
    MerchantApiResponse<MerchantResponses.Mutation> create(
        @RequestBody JsonNode body, HttpServletRequest request
    ) {
        MerchantJson.exactRequiredObject(body, CREATE_FIELDS);
        CreateRequest command = new CreateRequest(MerchantJson.positiveLongString(body,
            "targetTenantId"), MerchantJson.uuid(body, "idempotencyKey"),
            MerchantOnboardingJson.profile(body.get("profile")));
        return MerchantApiResponse.success(MerchantOnboardingResponses.mutation(
            service.create(subjects.actor(request), command)), trace);
    }

    @PostMapping("/merchants/{merchantId}/amendments")
    MerchantApiResponse<MerchantOnboardingResponses.AmendmentSubmission> amend(
        @PathVariable long merchantId, @RequestBody JsonNode body, HttpServletRequest request
    ) {
        MerchantJson.exactRequiredObject(body, AMENDMENT_FIELDS);
        AmendmentRequest command = new AmendmentRequest(merchantId,
            MerchantJson.nonNegativeLong(body, "expectedMerchantVersion"),
            MerchantJson.uuid(body, "idempotencyKey"),
            MerchantOnboardingJson.profile(body.get("profile")));
        return MerchantApiResponse.success(MerchantOnboardingResponses.submission(
            service.createAmendment(subjects.actor(request), command)), trace);
    }

    @GetMapping("/merchants/{merchantId}/amendments/pending")
    MerchantApiResponse<MerchantOnboardingResponses.Pending> pending(
        @PathVariable long merchantId, HttpServletRequest request
    ) {
        return MerchantApiResponse.success(MerchantOnboardingResponses.pending(
            service.pendingAmendment(subjects.actor(request), merchantId)), trace);
    }

    @PostMapping("/merchants/{merchantId}/amendments/{amendmentId}/review-decisions")
    MerchantApiResponse<MerchantOnboardingResponses.AmendmentReview> review(
        @PathVariable long merchantId, @PathVariable long amendmentId,
        @RequestBody JsonNode body, HttpServletRequest request
    ) {
        MerchantJson.exactRequiredObject(body, REVIEW_FIELDS);
        AmendmentDecision decision;
        try { decision = AmendmentDecision.valueOf(MerchantJson.requiredString(body, "decision")); }
        catch (IllegalArgumentException exception) { throw MerchantJson.invalid(); }
        AmendmentReviewRequest command = new AmendmentReviewRequest(merchantId, amendmentId,
            MerchantJson.nonNegativeLong(body, "expectedVersion"),
            MerchantJson.uuid(body, "idempotencyKey"), decision,
            MerchantJson.requiredString(body, "reasonCode"));
        return MerchantApiResponse.success(MerchantOnboardingResponses.review(
            service.reviewAmendment(subjects.actor(request), command)), trace);
    }

    private static int integer(String value, int fallback) {
        if (value == null) return fallback;
        try { return Integer.parseInt(value); }
        catch (NumberFormatException exception) { throw MerchantJson.invalid(); }
    }
}
