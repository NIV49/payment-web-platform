package com.niv.payment.identity.oidc;

import com.niv.payment.identity.lifecycle.MemberInvitationService;
import com.niv.payment.identity.lifecycle.TenantBootstrapService;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(-100)
@RestControllerAdvice(assignableTypes = {
    IdentityGovernanceController.class, TenantBootstrapController.class
})
final class IdentityGovernanceExceptionHandler {
    private final OidcRequestTrace trace;

    IdentityGovernanceExceptionHandler(OidcRequestTrace trace) {
        this.trace = trace;
    }

    @ExceptionHandler({MemberInvitationService.StepUpRequiredException.class,
        TenantBootstrapService.StepUpRequiredException.class})
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
        return failure(HttpStatus.CONFLICT, 40901, "IDENTITY_GOVERNANCE_CONFLICT",
            "Identity governance conflicts with current state");
    }

    @ExceptionHandler(IdentityProvisioningException.class)
    ResponseEntity<ErrorResponse> provisioningUnavailable() {
        return failure(HttpStatus.SERVICE_UNAVAILABLE, 50301,
            "IDENTITY_PROVISIONING_UNAVAILABLE", "Identity provisioning is unavailable");
    }

    private ResponseEntity<ErrorResponse> failure(HttpStatus status, int code,
                                                  String error, String message) {
        return ResponseEntity.status(status)
            .body(new ErrorResponse(code, null, error, message, trace.current()));
    }

    record ErrorResponse(int code, Void data, String error, String message, String traceId) { }
}
