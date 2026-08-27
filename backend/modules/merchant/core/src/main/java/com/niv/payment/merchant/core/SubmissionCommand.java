package com.niv.payment.merchant.core;

import java.util.Optional;
import java.util.UUID;

public final class SubmissionCommand {
    private final MerchantCommand command;
    private final UUID idempotencyKey;
    private final Long expectedVersion;
    private final String legalName;
    private final String displayName;
    private final String registrationCountry;
    private final String normalizedRegistrationNumber;

    SubmissionCommand(MerchantCommand command, UUID idempotencyKey, Long expectedVersion,
                      String legalName, String displayName, String registrationCountry,
                      String normalizedRegistrationNumber) {
        this.command = command;
        this.idempotencyKey = idempotencyKey;
        this.expectedVersion = expectedVersion;
        this.legalName = legalName;
        this.displayName = displayName;
        this.registrationCountry = registrationCountry;
        this.normalizedRegistrationNumber = normalizedRegistrationNumber;
    }

    public MerchantCommand command() { return command; }
    public UUID idempotencyKey() { return idempotencyKey; }
    public Long expectedVersion() { return expectedVersion; }
    public String legalName() { return legalName; }
    public String displayName() { return displayName; }
    public String registrationCountry() { return registrationCountry; }
    public String normalizedRegistrationNumber() { return normalizedRegistrationNumber; }
    public Optional<String> normalizedRegistrationNumberOptional() {
        return Optional.ofNullable(normalizedRegistrationNumber);
    }

    @Override
    public String toString() {
        return "SubmissionCommand[command=" + command + ", idempotencyKey=" + idempotencyKey
            + ", expectedVersion=" + expectedVersion + ", registrationNumber=<redacted>]";
    }
}
