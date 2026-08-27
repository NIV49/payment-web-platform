package com.niv.payment.merchant.core;

import java.util.UUID;

public final class SubmissionRequest {
    private final UUID idempotencyKey;
    private final Long expectedVersion;
    private final String legalName;
    private final String displayName;
    private final String registrationCountry;
    private final String registrationNumber;
    private final boolean expectedVersionPresent;

    public SubmissionRequest(UUID idempotencyKey, Long expectedVersion, String legalName,
                             String displayName, String registrationCountry,
                             String registrationNumber, boolean expectedVersionPresent) {
        this.idempotencyKey = idempotencyKey;
        this.expectedVersion = expectedVersion;
        this.legalName = legalName;
        this.displayName = displayName;
        this.registrationCountry = registrationCountry;
        this.registrationNumber = registrationNumber;
        this.expectedVersionPresent = expectedVersionPresent;
    }

    public UUID idempotencyKey() { return idempotencyKey; }
    public Long expectedVersion() { return expectedVersion; }
    public String legalName() { return legalName; }
    public String displayName() { return displayName; }
    public String registrationCountry() { return registrationCountry; }
    public String registrationNumber() { return registrationNumber; }
    public boolean expectedVersionPresent() { return expectedVersionPresent; }

    @Override
    public String toString() {
        return "SubmissionRequest[idempotencyKey=" + idempotencyKey
            + ", expectedVersion=" + expectedVersion + ", registrationNumber=<redacted>]";
    }
}
