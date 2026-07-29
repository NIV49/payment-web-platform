package com.niv.payment.adminapi.web;

import java.util.UUID;

public final class RequestTrace {
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private RequestTrace() { }

    public static String begin() {
        String id = UUID.randomUUID().toString();
        CURRENT.set(id);
        return id;
    }

    public static String current() {
        String id = CURRENT.get();
        return id == null ? UUID.randomUUID().toString() : id;
    }

    public static void end() {
        CURRENT.remove();
    }
}
