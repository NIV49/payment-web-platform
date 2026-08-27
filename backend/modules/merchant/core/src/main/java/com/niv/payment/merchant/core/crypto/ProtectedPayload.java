package com.niv.payment.merchant.core.crypto;

import java.util.Objects;

public record ProtectedPayload(
    int aadSchemeVersion,
    int protectionVersion,
    String algorithm,
    String keyId,
    byte[] nonce,
    byte[] ciphertext,
    byte[] authenticationTag,
    String masked
) {
    public ProtectedPayload {
        algorithm = Objects.requireNonNull(algorithm, "algorithm");
        keyId = Objects.requireNonNull(keyId, "keyId");
        nonce = Objects.requireNonNull(nonce, "nonce").clone();
        ciphertext = Objects.requireNonNull(ciphertext, "ciphertext").clone();
        authenticationTag = Objects.requireNonNull(authenticationTag, "authenticationTag").clone();
    }

    @Override public byte[] nonce() { return nonce.clone(); }
    @Override public byte[] ciphertext() { return ciphertext.clone(); }
    @Override public byte[] authenticationTag() { return authenticationTag.clone(); }

    @Override
    public String toString() {
        return "ProtectedPayload[aadSchemeVersion=" + aadSchemeVersion
            + ",protectionVersion=" + protectionVersion + ",algorithm=" + algorithm
            + ",keyId=" + keyId + ",nonce=<redacted>,ciphertext=<redacted>"
            + ",authenticationTag=<redacted>,masked=" + masked + "]";
    }
}
