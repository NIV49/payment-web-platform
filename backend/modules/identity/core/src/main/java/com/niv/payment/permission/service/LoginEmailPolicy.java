package com.niv.payment.permission.service;

import java.util.Locale;

/** Canonical login-email policy; it deliberately avoids provider-specific rewriting. */
public final class LoginEmailPolicy {
    private static final int MAX_EMAIL_LENGTH = 100;
    private static final int MAX_LOCAL_PART_LENGTH = 64;

    private LoginEmailPolicy() {
    }

    public static String normalize(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Login email is required");
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty() || normalized.length() > MAX_EMAIL_LENGTH) {
            throw new IllegalArgumentException("Login email is invalid");
        }
        int separator = normalized.indexOf('@');
        if (separator < 1 || separator != normalized.lastIndexOf('@')
            || separator > MAX_LOCAL_PART_LENGTH || separator == normalized.length() - 1) {
            throw new IllegalArgumentException("Login email is invalid");
        }
        String localPart = normalized.substring(0, separator);
        String domain = normalized.substring(separator + 1);
        if (localPart.startsWith(".") || localPart.endsWith(".")
            || localPart.contains("..") || !validLocalPart(localPart)
            || !validDomain(domain)) {
            throw new IllegalArgumentException("Login email is invalid");
        }
        return normalized;
    }

    private static boolean validLocalPart(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character >= 'a' && character <= 'z'
                || character >= '0' && character <= '9'
                || ".!#$%&'*+/=?^_`{|}~-".indexOf(character) >= 0) {
                continue;
            }
            return false;
        }
        return true;
    }

    private static boolean validDomain(String domain) {
        if (domain.length() > 253 || !domain.contains(".") || domain.contains("..")) {
            return false;
        }
        for (String label : domain.split("\\.", -1)) {
            if (label.isEmpty() || label.length() > 63
                || label.startsWith("-") || label.endsWith("-")) {
                return false;
            }
            for (int index = 0; index < label.length(); index++) {
                char character = label.charAt(index);
                if (!(character >= 'a' && character <= 'z')
                    && !(character >= '0' && character <= '9')
                    && character != '-') {
                    return false;
                }
            }
        }
        return true;
    }
}
