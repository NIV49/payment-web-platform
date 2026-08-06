package com.niv.payment.identity.lifecycle;

public record FederatedIdentity(String issuer, String subject, String username, Mode mode) {
    public FederatedIdentity(String issuer, String subject, String username) {
        this(issuer, subject, username, Mode.NEW_DISABLED);
    }

    public FederatedIdentity {
        issuer = requireText(issuer, "issuer", 512);
        subject = requireText(subject, "subject", 128);
        username = requireText(username, "username", 128);
        if (mode == null) {
            throw new IllegalArgumentException("mode is required");
        }
    }

    private static String requireText(String value, String name, int maximumLength) {
        if (value == null || value.isBlank() || value.length() > maximumLength) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return value;
    }

    public enum Mode {
        NEW_DISABLED,
        EXISTING_ACTIVE
    }
}
