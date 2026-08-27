package com.niv.payment.dictionary.core;

public final class SystemDictionaryException {
    private SystemDictionaryException() { }

    public static final class InvalidRequest extends IllegalArgumentException {
        public InvalidRequest(String message) { super(message); }
    }

    public static final class NotFound extends RuntimeException {
        public NotFound(String message) { super(message); }
    }

    public static final class DataConflict extends RuntimeException {
        public DataConflict(String message) { super(message); }
    }

    public static final class OptimisticLockConflict extends RuntimeException {
        public OptimisticLockConflict() { super("The dictionary record has changed"); }
    }
}
