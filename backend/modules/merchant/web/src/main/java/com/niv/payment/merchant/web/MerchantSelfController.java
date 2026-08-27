package com.niv.payment.merchant.web;

import com.niv.payment.merchant.core.MerchantApplicationService;
import com.niv.payment.merchant.core.SubmissionRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

import java.util.Set;

@RestController
@RequestMapping("/api/merchant/application")
final class MerchantSelfController {
    private static final Set<String> SUBMISSION_FIELDS = Set.of("idempotencyKey",
        "expectedVersion", "legalName", "displayName", "registrationCountry",
        "registrationNumber");
    private final MerchantApplicationService merchants;
    private final MerchantSubjectAdapter subjects;
    private final MerchantRequestTrace trace;

    @Autowired
    MerchantSelfController(MerchantApplicationService merchants, MerchantSubjectAdapter subjects,
                           MerchantRequestTrace trace) {
        this.merchants = merchants;
        this.subjects = subjects;
        this.trace = trace;
    }

    MerchantSelfController(MerchantApplicationService merchants, MerchantSubjectAdapter subjects) {
        this(merchants, subjects, () -> "test-trace");
    }

    @GetMapping
    MerchantApiResponse<MerchantResponses.SelfApplication> application(HttpServletRequest request) {
        var merchant = merchants.findSelf(subjects.actor(request))
            .map(MerchantResponses::selfDetail).orElse(null);
        return MerchantApiResponse.success(new MerchantResponses.SelfApplication(merchant), trace);
    }

    @PostMapping("/submissions")
    MerchantApiResponse<MerchantResponses.Mutation> submit(
        @RequestBody JsonNode body, HttpServletRequest request) {
        MerchantJson.exactObject(body, SUBMISSION_FIELDS);
        Long expectedVersion = MerchantJson.nullableRequiredNonNegativeLong(body, "expectedVersion");
        if (body.has("registrationNumber") && body.get("registrationNumber").isNull()) {
            throw MerchantJson.invalid();
        }
        var command = new SubmissionRequest(MerchantJson.uuid(body, "idempotencyKey"),
            expectedVersion, MerchantJson.requiredString(body, "legalName"),
            MerchantJson.requiredString(body, "displayName"),
            MerchantJson.requiredString(body, "registrationCountry"),
            MerchantJson.optionalString(body, "registrationNumber"), true);
        return MerchantApiResponse.success(
            MerchantResponses.mutation(merchants.submit(subjects.actor(request), command)), trace);
    }
}
