package com.niv.payment.merchant.web;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MerchantOnboardingHttpContractTest {
    @Test
    void exposesOnlyTheFrozenMch003OnboardingAndDocumentEndpoints() {
        Set<String> endpoints = new HashSet<>();
        collect(MerchantOnboardingController.class, endpoints);
        collect(MerchantDocumentController.class, endpoints);

        assertThat(endpoints).containsExactlyInAnyOrder(
            "GET /api/platform/merchant-onboarding/eligible-tenants",
            "POST /api/platform/merchant-document-uploads",
            "DELETE /api/platform/merchant-document-uploads/{documentId}",
            "GET /api/platform/merchant-document-uploads/{documentId}/content",
            "GET /api/platform/merchants/{merchantId}/documents/{kind}/content",
            "POST /api/platform/merchants",
            "POST /api/platform/merchants/{merchantId}/amendments",
            "GET /api/platform/merchants/{merchantId}/amendments/pending",
            "POST /api/platform/merchants/{merchantId}/amendments/{amendmentId}/review-decisions"
        );
    }

    private static void collect(Class<?> controller, Set<String> endpoints) {
        String base = controller.getAnnotation(RequestMapping.class).value()[0];
        for (Method method : controller.getDeclaredMethods()) {
            GetMapping get = method.getAnnotation(GetMapping.class);
            if (get != null) endpoints.add("GET " + base + path(get.value()));
            PostMapping post = method.getAnnotation(PostMapping.class);
            if (post != null) endpoints.add("POST " + base + path(post.value()));
            DeleteMapping delete = method.getAnnotation(DeleteMapping.class);
            if (delete != null) endpoints.add("DELETE " + base + path(delete.value()));
        }
    }

    private static String path(String[] values) {
        return values.length == 0 ? "" : values[0];
    }
}
