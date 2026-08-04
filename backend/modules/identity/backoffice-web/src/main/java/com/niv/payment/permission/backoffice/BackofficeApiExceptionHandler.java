package com.niv.payment.permission.backoffice;

import cn.dev33.satoken.exception.NotLoginException;
import com.niv.payment.permission.security.InvalidSessionException;
import com.niv.payment.permission.service.AuthenticationService;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Objects;

@RestControllerAdvice
final class BackofficeApiExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(BackofficeApiExceptionHandler.class);
    private final AuthenticationService authentication;

    BackofficeApiExceptionHandler(AuthenticationService authentication) {
        this.authentication = Objects.requireNonNull(authentication, "authentication");
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class,
        HttpMessageNotReadableException.class, IllegalArgumentException.class})
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

    @ExceptionHandler(InvalidSessionException.class)
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

    @ExceptionHandler(BackofficeAccessDeniedException.class)
    ResponseEntity<BackofficeApiResponse<Void>> forbidden() {
        return failure(HttpStatus.FORBIDDEN, 40301, "PERMISSION_DENIED", "Permission denied");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<BackofficeApiResponse<Void>> notFound() {
        return failure(HttpStatus.NOT_FOUND, 40401, "RESOURCE_NOT_FOUND", "Resource not found");
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
