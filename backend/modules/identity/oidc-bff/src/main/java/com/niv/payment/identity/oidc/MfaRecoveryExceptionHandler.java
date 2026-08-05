package com.niv.payment.identity.oidc;

import com.niv.payment.identity.lifecycle.MfaRecoveryService;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(-100)
@RestControllerAdvice(assignableTypes = MfaRecoveryController.class)
final class MfaRecoveryExceptionHandler {
    private final OidcRequestTrace trace;

    MfaRecoveryExceptionHandler(OidcRequestTrace trace) {
        this.trace = trace;
    }

    @ExceptionHandler(MfaRecoveryService.StepUpRequiredException.class)
    ResponseEntity<ErrorResponse> stepUpRequired() {
        return failure(HttpStatus.FORBIDDEN, 40302, "STEP_UP_REQUIRED",
            "A recent step-up is required");
    }

    @ExceptionHandler(SecurityException.class)
    ResponseEntity<ErrorResponse> forbidden() {
        return failure(HttpStatus.FORBIDDEN, 40301, "PERMISSION_DENIED", "Permission denied");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ErrorResponse> invalid() {
        return failure(HttpStatus.BAD_REQUEST, 40001, "INVALID_REQUEST", "Invalid request");
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<ErrorResponse> conflict() {
        return failure(HttpStatus.CONFLICT, 40901, "MFA_RECOVERY_CONFLICT",
            "MFA recovery conflicts with current identity state");
    }

    private ResponseEntity<ErrorResponse> failure(HttpStatus status, int code,
                                                  String error, String message) {
        return ResponseEntity.status(status)
            .body(new ErrorResponse(code, null, error, message, trace.current()));
    }

    record ErrorResponse(int code, Void data, String error, String message, String traceId) { }
}
