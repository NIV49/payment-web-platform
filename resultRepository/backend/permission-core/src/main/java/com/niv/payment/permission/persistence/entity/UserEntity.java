package com.niv.payment.permission.persistence.entity;

import java.time.Instant;

public record UserEntity(
    long id,
    String idpIssuer,
    String idpSubject,
    String displayName,
    byte[] emailCipher,
    byte[] phoneCipher,
    String status,
    Instant createdAt,
    Instant updatedAt,
    long rowVersion
) {
    public UserEntity {
        emailCipher = emailCipher == null ? null : emailCipher.clone();
        phoneCipher = phoneCipher == null ? null : phoneCipher.clone();
    }

    @Override
    public byte[] emailCipher() {
        return emailCipher == null ? null : emailCipher.clone();
    }

    @Override
    public byte[] phoneCipher() {
        return phoneCipher == null ? null : phoneCipher.clone();
    }
}
