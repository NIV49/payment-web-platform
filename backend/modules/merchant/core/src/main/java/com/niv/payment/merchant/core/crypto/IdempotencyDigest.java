package com.niv.payment.merchant.core.crypto;

import java.security.MessageDigest;
import java.util.Objects;

public record IdempotencyDigest(
    int schemeVersion,
    String algorithm,
    String keyId,
    byte[] value
) {
    public IdempotencyDigest {
        algorithm = Objects.requireNonNull(algorithm, "algorithm");
        keyId = Objects.requireNonNull(keyId, "keyId");
        value = Objects.requireNonNull(value, "value").clone();
    }

    @Override
    public byte[] value() {
        return value.clone();
    }

    public boolean matches(IdempotencyDigest other) {
        return other != null
            && schemeVersion == other.schemeVersion
            && algorithm.equals(other.algorithm)
            && keyId.equals(other.keyId)
            && MessageDigest.isEqual(value, other.value);
    }

    @Override
    public String toString() {
        return "IdempotencyDigest[schemeVersion=" + schemeVersion
            + ", algorithm=" + algorithm
            + ", keyId=" + keyId
            + ", value=<redacted>]";
    }
}
