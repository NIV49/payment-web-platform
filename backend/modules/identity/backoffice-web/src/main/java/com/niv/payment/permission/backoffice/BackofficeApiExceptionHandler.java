package com.niv.payment.permission.backoffice;

import cn.dev33.satoken.exception.NotLoginException;
import com.niv.payment.permission.security.InvalidSessionException;
import com.niv.payment.permission.port.InvalidAuthorizationSubjectException;
import com.niv.payment.permission.port.StalePermissionVersionException;
import com.niv.payment.permission.service.IdentityAdministrationService;
import com.niv.payment.permission.service.RoleAssignmentPolicy;
import com.niv.payment.permission.service.RoleGrantAdministrationService;
import com.niv.payment.permission.service.RoleConfigurationAdministrationService;
import com.niv.payment.permission.service.AuthenticationService;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Objects;
import com.niv.payment.dictionary.core.SystemDictionaryException;

@RestControllerAdvice
final class BackofficeApiExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(BackofficeApiExceptionHandler.class);
    private final AuthenticationService authentication;

    BackofficeApiExceptionHandler(AuthenticationService authentication) {
        this.authentication = Objects.requireNonNull(authentication, "authentication");
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class,
        HttpMessageNotReadableException.class, MissingServletRequestParameterException.class,
        MethodArgumentTypeMismatchException.class,
        IdentityAdministrationService.InvalidCommandException.class,
        SystemDictionaryException.InvalidRequest.class,
        IllegalArgumentException.class})
    ResponseEntity<BackofficeApiResponse<Void>> badRequest(Exception exception) {
        return failure(HttpStatus.BAD_REQUEST, 40001, "INVALID_REQUEST", "Invalid request");
    }

    @ExceptionHandler({AuthenticationService.AuthenticationFailedException.class, NotLoginException.class})
    ResponseEntity<BackofficeApiResponse<Void>> unauthorized(Exception exception) {
        boolean credentialFailure = exception instanceof AuthenticationService.AuthenticationFailedException;
        return failure(HttpStatus.UNAUTHORIZED, 40101,
            credentialFailure ? "INVALID_CREDENTIALS" : "AUTH_REQUIRED",
            credentialFailure ? "Invalid username or password" : "Authentication is required");
    }

    @ExceptionHandler({InvalidSessionException.class, InvalidAuthorizationSubjectException.class,
        StalePermissionVersionException.class})
    ResponseEntity<BackofficeApiResponse<Void>> staleSession(Exception exception) {
        try {
            authentication.logout();
        } catch (RuntimeException cleanupFailure) {
            LOG.warn("Failed to clear invalid backoffice session", cleanupFailure);
        }
        return failure(HttpStatus.UNAUTHORIZED, 40102, "SESSION_INVALID", "Session is invalid or expired");
    }

    @ExceptionHandler(AuthenticationService.RateLimitExceededException.class)
    ResponseEntity<BackofficeApiResponse<Void>> rateLimited() {
        return failure(HttpStatus.TOO_MANY_REQUESTS, 42901,
            "LOGIN_RATE_LIMITED", "Too many login attempts");
    }

    @ExceptionHandler({BackofficeAccessDeniedException.class, SecurityException.class})
    ResponseEntity<BackofficeApiResponse<Void>> forbidden() {
        return failure(HttpStatus.FORBIDDEN, 40301, "PERMISSION_DENIED", "Permission denied");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<BackofficeApiResponse<Void>> notFound() {
        return failure(HttpStatus.NOT_FOUND, 40401, "RESOURCE_NOT_FOUND", "Resource not found");
    }

    @ExceptionHandler({IdentityAdministrationService.ResourceNotFoundException.class,
        SystemDictionaryException.NotFound.class})
    ResponseEntity<BackofficeApiResponse<Void>> missingIdentityResource(
        RuntimeException exception) {
        return failure(HttpStatus.NOT_FOUND, 40401, "RESOURCE_NOT_FOUND", exception.getMessage());
    }

    @ExceptionHandler({IdentityAdministrationService.OptimisticLockException.class,
        SystemDictionaryException.OptimisticLockConflict.class})
    ResponseEntity<BackofficeApiResponse<Void>> optimisticLockConflict() {
        return failure(HttpStatus.CONFLICT, 40902, "OPTIMISTIC_LOCK_CONFLICT",
            "The record has changed; reload and retry");
    }

    @ExceptionHandler({IdentityAdministrationService.DataConflictException.class,
        SystemDictionaryException.DataConflict.class,
        DataIntegrityViolationException.class})
    ResponseEntity<BackofficeApiResponse<Void>> dataConflict() {
        return failure(HttpStatus.CONFLICT, 40901, "DATA_CONFLICT",
            "The operation conflicts with current data");
    }

    @ExceptionHandler(RoleAssignmentPolicy.RoleNotAssignableException.class)
    ResponseEntity<BackofficeApiResponse<Void>> roleNotAssignable() {
        return failure(HttpStatus.UNPROCESSABLE_CONTENT, 42201, "IAM_ROLE_NOT_ASSIGNABLE",
            "The requested role change is not allowed");
    }

    @ExceptionHandler(RoleAssignmentPolicy.LastAdministratorException.class)
    ResponseEntity<BackofficeApiResponse<Void>> lastAdministratorProtected() {
        return failure(HttpStatus.UNPROCESSABLE_CONTENT, 42202, "IAM_LAST_ADMIN_PROTECTED",
            "The last active system administrator cannot be disabled or removed");
    }

    @ExceptionHandler({RoleGrantAdministrationService.LegacyAdministrationCutoverRequiredException.class,
        RoleConfigurationAdministrationService.LegacyAdministrationCutoverRequiredException.class})
    ResponseEntity<BackofficeApiResponse<Void>> legacyAdministrationCutoverRequired() {
        return failure(HttpStatus.CONFLICT, 40903,
            "LEGACY_ADMINISTRATION_CUTOVER_REQUIRED",
            "Role grant editing is unavailable until the legacy administration cutover is complete");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<BackofficeApiResponse<Void>> unexpected(Exception exception) {
        LOG.error("Unhandled backoffice request failure", exception);
        return failure(HttpStatus.INTERNAL_SERVER_ERROR, 50001, "INTERNAL_ERROR", "Internal server error");
    }

    private static ResponseEntity<BackofficeApiResponse<Void>> failure(
        HttpStatus status, int code, String error, String message) {
        return ResponseEntity.status(status).body(BackofficeApiResponse.failure(code, error, message));
    }
}
