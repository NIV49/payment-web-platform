package com.niv.payment.adminapi.web;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class RequestBodySizeLimitFilterTest {
    @Test
    void rejectsOversizedApiBodyWithAStableEnvelopeBeforeDispatch() throws Exception {
        RequestBodySizeLimitFilter filter = new RequestBodySizeLimitFilter(new ObjectMapper(), 32);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        request.setContent("x".repeat(33).getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("PAYLOAD_TOO_LARGE");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void replaysAnAcceptedBodyToDownstreamCode() throws Exception {
        RequestBodySizeLimitFilter filter = new RequestBodySizeLimitFilter(new ObjectMapper(), 32);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        request.setContent("accepted".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest().getInputStream().readAllBytes())
            .isEqualTo("accepted".getBytes(StandardCharsets.UTF_8));
    }
}
