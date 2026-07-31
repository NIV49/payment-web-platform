package com.niv.payment.adminapi.web;

import cn.dev33.satoken.exception.NotLoginException;
import com.niv.payment.permission.port.InvalidAuthorizationSubjectException;
import com.niv.payment.permission.port.StalePermissionVersionException;
import com.niv.payment.permission.service.AuthenticationService;
import com.niv.payment.permission.service.IdentityAdministrationService;
import com.niv.payment.permission.service.RoleAssignmentPolicy;
import com.niv.payment.permission.service.RoleGrantAdministrationService;
import com.niv.payment.permission.security.InvalidSessionException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Objects;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private final AuthenticationService authentication;

    public ApiExceptionHandler(AuthenticationService authentication) {
        this.authentication = Objects.requireNonNull(authentication, "authentication");
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class,
        HttpMessageNotReadableException.class, IdentityAdministrationService.InvalidCommandException.class,
        IllegalArgumentException.class})
    ResponseEntity<ApiResponse<Void>> badRequest(Exception exception) {
        return failure(HttpStatus.BAD_REQUEST, 40001, "INVALID_REQUEST", "Invalid request");
    }

    @ExceptionHandler({AuthenticationService.AuthenticationFailedException.class, NotLoginException.class})
    ResponseEntity<ApiResponse<Void>> unauthorized(Exception exception) {
        return failure(HttpStatus.UNAUTHORIZED, 40101,
            exception instanceof AuthenticationService.AuthenticationFailedException ? "INVALID_CREDENTIALS" : "AUTH_REQUIRED",
            exception instanceof AuthenticationService.AuthenticationFailedException
                ? "Invalid username or password" : "Authentication is required");
    }

    @ExceptionHandler({InvalidSessionException.class, InvalidAuthorizationSubjectException.class})
    ResponseEntity<ApiResponse<Void>> staleSession(Exception exception) {
        try {
            authentication.logout();
        } catch (RuntimeException cleanupFailure) {
            LOG.warn("Failed to clear invalid session, traceId={}", RequestTrace.current(), cleanupFailure);
        }
        return failure(HttpStatus.UNAUTHORIZED, 40102, "SESSION_INVALID", "Session is invalid or expired");
    }

    @ExceptionHandler(StalePermissionVersionException.class)
    ResponseEntity<ApiResponse<Void>> stalePermissionVersion(StalePermissionVersionException exception) {
        return staleSession(exception);
    }

    @ExceptionHandler(AuthenticationService.RateLimitExceededException.class)
    ResponseEntity<ApiResponse<Void>> rateLimited() {
        return failure(HttpStatus.TOO_MANY_REQUESTS, 42901, "LOGIN_RATE_LIMITED", "Too many login attempts");
    }

    @ExceptionHandler({AccessDeniedException.class, SecurityException.class})
    ResponseEntity<ApiResponse<Void>> forbidden() {
        return failure(HttpStatus.FORBIDDEN, 40301, "PERMISSION_DENIED", "Permission denied");
    }

    @ExceptionHandler(RoleAssignmentPolicy.RoleNotAssignableException.class)
    ResponseEntity<ApiResponse<Void>> roleNotAssignable() {
        return failure(HttpStatus.UNPROCESSABLE_CONTENT, 42201, "IAM_ROLE_NOT_ASSIGNABLE",
            "The requested role change is not allowed");
    }

    @ExceptionHandler(RoleAssignmentPolicy.LastAdministratorException.class)
    ResponseEntity<ApiResponse<Void>> lastAdministratorProtected() {
        return failure(HttpStatus.UNPROCESSABLE_CONTENT, 42202, "IAM_LAST_ADMIN_PROTECTED",
            "The last active system administrator cannot be disabled or removed");
    }

    @ExceptionHandler(IdentityAdministrationService.ResourceNotFoundException.class)
    ResponseEntity<ApiResponse<Void>> notFound(IdentityAdministrationService.ResourceNotFoundException exception) {
        return failure(HttpStatus.NOT_FOUND, 40401, "RESOURCE_NOT_FOUND", exception.getMessage());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiResponse<Void>> missingResource() {
        return failure(HttpStatus.NOT_FOUND, 40401, "RESOURCE_NOT_FOUND", "Resource not found");
    }

    @ExceptionHandler(IdentityAdministrationService.OptimisticLockException.class)
    ResponseEntity<ApiResponse<Void>> optimisticLockConflict(
        IdentityAdministrationService.OptimisticLockException exception) {
        return failure(HttpStatus.CONFLICT, 40902, "OPTIMISTIC_LOCK_CONFLICT",
            "The record has changed; reload and retry");
    }

    @ExceptionHandler(RoleGrantAdministrationService.LegacyAdministrationCutoverRequiredException.class)
    ResponseEntity<ApiResponse<Void>> legacyAdministrationCutoverRequired() {
        return failure(HttpStatus.CONFLICT, 40903, "LEGACY_ADMINISTRATION_CUTOVER_REQUIRED",
            "Role grant editing is unavailable until the legacy administration cutover is complete");
    }

    @ExceptionHandler({IdentityAdministrationService.DataConflictException.class,
        DataIntegrityViolationException.class})
    ResponseEntity<ApiResponse<Void>> dataConflict(Exception exception) {
        return failure(HttpStatus.CONFLICT, 40901, "DATA_CONFLICT", "The operation conflicts with current data");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiResponse<Void>> unexpected(Exception exception) {
        LOG.error("Unhandled request failure, traceId={}", RequestTrace.current(), exception);
        return failure(HttpStatus.INTERNAL_SERVER_ERROR, 50001, "INTERNAL_ERROR", "Internal server error");
    }

    private static ResponseEntity<ApiResponse<Void>> failure(HttpStatus status, int code, String error, String message) {
        return ResponseEntity.status(status).body(ApiResponse.failure(code, error, message));
    }
}
