package com.niv.payment.merchant.web;

import com.niv.payment.merchant.core.MerchantException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {MerchantSelfController.class,
    MerchantPlatformController.class, MerchantOnboardingController.class,
    MerchantDocumentController.class})
final class MerchantHttpExceptionHandler {
    private final MerchantRequestTrace trace;

    MerchantHttpExceptionHandler(MerchantRequestTrace trace) {
        this.trace = trace;
    }

    @ExceptionHandler({MerchantException.InvalidRequest.class,
        HttpMessageNotReadableException.class, MethodArgumentNotValidException.class,
        MethodArgumentTypeMismatchException.class, IllegalArgumentException.class})
    ResponseEntity<MerchantApiResponse<Void>> invalid(Exception exception) {
        return failure(HttpStatus.BAD_REQUEST, 40001, "INVALID_REQUEST", "Invalid request");
    }

    @ExceptionHandler(MerchantException.PermissionDenied.class)
    ResponseEntity<MerchantApiResponse<Void>> permission(MerchantException.PermissionDenied exception) {
        return failure(HttpStatus.FORBIDDEN, 40301, "PERMISSION_DENIED", "Permission denied");
    }

    @ExceptionHandler(MerchantException.ResourceNotFound.class)
    ResponseEntity<MerchantApiResponse<Void>> notFound(MerchantException.ResourceNotFound exception) {
        return failure(HttpStatus.NOT_FOUND, 40401, "RESOURCE_NOT_FOUND", "Resource not found");
    }

    @ExceptionHandler(MerchantException.DataConflict.class)
    ResponseEntity<MerchantApiResponse<Void>> dataConflict(MerchantException.DataConflict exception) {
        return failure(HttpStatus.CONFLICT, 40901, "DATA_CONFLICT",
            "The operation conflicts with current data");
    }

    @ExceptionHandler(MerchantException.OptimisticLockConflict.class)
    ResponseEntity<MerchantApiResponse<Void>> optimistic(
        MerchantException.OptimisticLockConflict exception) {
        return failure(HttpStatus.CONFLICT, 40902, "OPTIMISTIC_LOCK_CONFLICT",
            "The record has changed; reload and retry");
    }

    @ExceptionHandler(MerchantException.StateConflict.class)
    ResponseEntity<MerchantApiResponse<Void>> state(MerchantException.StateConflict exception) {
        return failure(HttpStatus.CONFLICT, 40910, "MERCHANT_STATE_CONFLICT",
            "The command is not legal from the current state");
    }

    @ExceptionHandler(MerchantException.IdempotencyConflict.class)
    ResponseEntity<MerchantApiResponse<Void>> idempotency(
        MerchantException.IdempotencyConflict exception) {
        return failure(HttpStatus.CONFLICT, 40911, "IDEMPOTENCY_CONFLICT",
            "The idempotency key was already used");
    }

    @ExceptionHandler(MerchantException.AmendmentAlreadyPending.class)
    ResponseEntity<MerchantApiResponse<Void>> amendmentPending(
        MerchantException.AmendmentAlreadyPending exception) {
        return failure(HttpStatus.CONFLICT, 40912, "AMENDMENT_ALREADY_PENDING",
            "A Merchant amendment is already pending");
    }

    @ExceptionHandler(MerchantException.DocumentAttachmentConflict.class)
    ResponseEntity<MerchantApiResponse<Void>> documentAttachment(
        MerchantException.DocumentAttachmentConflict exception) {
        return failure(HttpStatus.CONFLICT, 40913, "DOCUMENT_ATTACHMENT_CONFLICT",
            "The Merchant document is not attachable");
    }

    @ExceptionHandler(MerchantException.ProtectedFieldUnavailable.class)
    ResponseEntity<MerchantApiResponse<Void>> protection(
        MerchantException.ProtectedFieldUnavailable exception) {
        return failure(HttpStatus.SERVICE_UNAVAILABLE, 50301, "PROTECTED_FIELD_UNAVAILABLE",
            "Protected field service is unavailable");
    }

    private ResponseEntity<MerchantApiResponse<Void>> failure(HttpStatus status, int code,
                                                                String error, String message) {
        return ResponseEntity.status(status).body(
            MerchantApiResponse.failure(code, error, message, trace));
    }
}
