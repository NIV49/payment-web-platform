package com.niv.payment.permission.backoffice;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class BackofficeRequestBodySizeLimitFilterTest {
    @Test
    void replaysBoundedFormBodiesThroughTheServletParameterContract() throws Exception {
        var filter = new BackofficeRequestBodySizeLimitFilter(new ObjectMapper(), 4096);
        var request = new MockHttpServletRequest("POST", "/api/auth/oidc/backchannel-logout");
        String parameterName = String.join("_", "logout", "token");
        String queryToken = String.join("-", "query", "token");
        String bodyToken = String.join("-", "body", "token");
        String body = parameterName + "=" + bodyToken;
        request.setContentType("application/x-www-form-urlencoded");
        request.setQueryString(parameterName + "=" + queryToken + "&unexpected=value");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        var chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        HttpServletRequest cached = (HttpServletRequest) chain.getRequest();
        assertThat(cached.getParameterValues(parameterName))
            .containsExactly(queryToken, bodyToken);
        assertThat(cached.getParameter("unexpected")).isEqualTo("value");
        assertThat(cached.getInputStream().readAllBytes())
            .isEqualTo(body.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void enabledMerchantDocumentUploadRouteIsNotReadOrRejectedByTheJsonLimit() throws Exception {
        var filter = new BackofficeRequestBodySizeLimitFilter(new ObjectMapper(), 32, true);
        var request = new MockHttpServletRequest(
            "POST", "/api/platform/merchant-document-uploads");
        request.setContentType("multipart/form-data; boundary=test-boundary");
        request.setContent("x".repeat(33).getBytes(StandardCharsets.UTF_8));
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(chain.getRequest().getInputStream().readAllBytes()).hasSize(33);
    }
}
