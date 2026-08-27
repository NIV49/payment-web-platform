package com.niv.payment.merchant.core.crypto;

import java.security.MessageDigest;
import java.util.Objects;

public record RegistrationFingerprint(
    int schemeVersion,
    int normalizationVersion,
    String algorithm,
    String searchKeyId,
    byte[] value
) {
    public RegistrationFingerprint {
        algorithm = Objects.requireNonNull(algorithm, "algorithm");
        searchKeyId = Objects.requireNonNull(searchKeyId, "searchKeyId");
        value = Objects.requireNonNull(value, "value").clone();
    }

    @Override
    public byte[] value() {
        return value.clone();
    }

    public boolean matches(RegistrationFingerprint other) {
        return other != null
            && schemeVersion == other.schemeVersion
            && normalizationVersion == other.normalizationVersion
            && algorithm.equals(other.algorithm)
            && searchKeyId.equals(other.searchKeyId)
            && MessageDigest.isEqual(value, other.value);
    }

    @Override
    public String toString() {
        return "RegistrationFingerprint[schemeVersion=" + schemeVersion
            + ", normalizationVersion=" + normalizationVersion
            + ", algorithm=" + algorithm
            + ", searchKeyId=" + searchKeyId
            + ", value=<redacted>]";
    }
}
