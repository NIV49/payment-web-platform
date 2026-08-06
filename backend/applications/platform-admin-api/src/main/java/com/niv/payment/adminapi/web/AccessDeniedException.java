package com.niv.payment.adminapi.web;

public final class AccessDeniedException extends RuntimeException {
    public AccessDeniedException() {
        super("Permission denied");
    }
}
