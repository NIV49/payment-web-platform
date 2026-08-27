package com.niv.payment.merchant.core.crypto;

import java.util.Objects;

public record ProtectedRegistration(
    int aadSchemeVersion,
    int normalizationVersion,
    String algorithm,
    String aeadKeyId,
    byte[] nonce,
    byte[] ciphertext,
    byte[] authenticationTag,
    String masked,
    RegistrationFingerprint fingerprint
) {
    public ProtectedRegistration {
        algorithm = Objects.requireNonNull(algorithm, "algorithm");
        aeadKeyId = Objects.requireNonNull(aeadKeyId, "aeadKeyId");
        nonce = Objects.requireNonNull(nonce, "nonce").clone();
        ciphertext = Objects.requireNonNull(ciphertext, "ciphertext").clone();
        authenticationTag = Objects.requireNonNull(authenticationTag, "authenticationTag").clone();
        masked = Objects.requireNonNull(masked, "masked");
        fingerprint = Objects.requireNonNull(fingerprint, "fingerprint");
    }

    @Override
    public byte[] nonce() {
        return nonce.clone();
    }

    @Override
    public byte[] ciphertext() {
        return ciphertext.clone();
    }

    @Override
    public byte[] authenticationTag() {
        return authenticationTag.clone();
    }

    @Override
    public String toString() {
        return "ProtectedRegistration[aadSchemeVersion=" + aadSchemeVersion
            + ", normalizationVersion=" + normalizationVersion
            + ", algorithm=" + algorithm
            + ", aeadKeyId=" + aeadKeyId
            + ", nonce=<redacted>, ciphertext=<redacted>, authenticationTag=<redacted>"
            + ", masked=" + masked + ", fingerprint=<redacted>]";
    }
}
