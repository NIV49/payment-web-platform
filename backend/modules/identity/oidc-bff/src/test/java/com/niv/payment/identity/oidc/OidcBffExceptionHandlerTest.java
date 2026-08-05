package com.niv.payment.identity.oidc;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OidcBffExceptionHandlerTest {
    @Test
    void rejectedLoginUsesTheStandardTraceableEnvelope() {
        var response = new OidcBffExceptionHandler(() -> "trace-1").rejected();

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(response.getBody()).isEqualTo(new OidcBffExceptionHandler.OidcErrorResponse(
            40103, null, "OIDC_LOGIN_REJECTED", "OIDC login was rejected", "trace-1"));
    }
}
