package com.niv.payment.permission.port;

/** Signals that the active identity tuple disappeared while an authorization decision was loading. */
public final class InvalidAuthorizationSubjectException extends RuntimeException {
    public InvalidAuthorizationSubjectException() {
        super("The authorization subject is no longer active");
    }
}
