package com.niv.payment.identity.oidc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(-100)
@RestControllerAdvice(assignableTypes = OidcBffController.class)
final class OidcBffExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(OidcBffExceptionHandler.class);

    private final OidcRequestTrace trace;

    OidcBffExceptionHandler(OidcRequestTrace trace) {
        this.trace = trace;
    }

    @ExceptionHandler(OidcFlowService.LoginRejectedException.class)
    ResponseEntity<OidcErrorResponse> rejected(OidcFlowService.LoginRejectedException exception) {
        LOG.warn("OIDC login rejected reason={} traceId={}", exception.reason(), trace.current());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(new OidcErrorResponse(
                40103, null, "OIDC_LOGIN_REJECTED", "OIDC login was rejected", trace.current()));
    }

    @ExceptionHandler(OidcLogoutTokenVerifier.BackChannelLogoutRejectedException.class)
    ResponseEntity<OidcErrorResponse> rejectedBackChannelLogout(
        OidcLogoutTokenVerifier.BackChannelLogoutRejectedException exception) {
        LOG.warn("OIDC back-channel logout rejected reason={} traceId={}",
            exception.reason(), trace.current());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(new OidcErrorResponse(
                40003, null, "OIDC_LOGOUT_REJECTED", "OIDC back-channel logout was rejected", trace.current()));
    }

    record OidcErrorResponse(int code, Void data, String error, String message, String traceId) { }
}
