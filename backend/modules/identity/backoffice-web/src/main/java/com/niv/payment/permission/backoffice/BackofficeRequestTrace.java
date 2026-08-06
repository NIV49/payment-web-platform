package com.niv.payment.permission.backoffice;

import java.util.UUID;

public final class BackofficeRequestTrace {
    private static final ThreadLocal<String> TRACE = new ThreadLocal<>();

    static String begin() {
        String value = UUID.randomUUID().toString();
        TRACE.set(value);
        return value;
    }

    public static String current() {
        String value = TRACE.get();
        return value == null ? "unavailable" : value;
    }

    static void end() {
        TRACE.remove();
    }

    private BackofficeRequestTrace() {
    }
}
