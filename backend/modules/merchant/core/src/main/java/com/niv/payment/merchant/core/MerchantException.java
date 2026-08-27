package com.niv.payment.merchant.core;

public abstract class MerchantException extends RuntimeException {
    protected MerchantException(String message) {
        super(message);
    }

    public static final class InvalidRequest extends MerchantException {
        public InvalidRequest(String message) { super(message); }
    }

    public static final class PermissionDenied extends MerchantException {
        public PermissionDenied() { super("Permission denied"); }
    }

    public static final class ResourceNotFound extends MerchantException {
        public ResourceNotFound() { super("Merchant was not found"); }
    }

    public static final class DataConflict extends MerchantException {
        public DataConflict() { super("Merchant data conflict"); }
    }

    public static final class OptimisticLockConflict extends MerchantException {
        public OptimisticLockConflict() { super("Merchant version is stale"); }
    }

    public static final class StateConflict extends MerchantException {
        public StateConflict() { super("Merchant command is not legal from the current state"); }
    }

    public static final class IdempotencyConflict extends MerchantException {
        public IdempotencyConflict() { super("Idempotency key was already used"); }
    }

    public static final class AmendmentAlreadyPending extends MerchantException {
        public AmendmentAlreadyPending() { super("A Merchant amendment is already pending"); }
    }

    public static final class DocumentAttachmentConflict extends MerchantException {
        public DocumentAttachmentConflict() { super("Merchant document is not attachable"); }
    }

    public static final class ProtectedFieldUnavailable extends MerchantException {
        public ProtectedFieldUnavailable() { super("Protected field service is unavailable"); }
        public ProtectedFieldUnavailable(Throwable cause) {
            super("Protected field service is unavailable");
            initCause(cause);
        }
    }
}
