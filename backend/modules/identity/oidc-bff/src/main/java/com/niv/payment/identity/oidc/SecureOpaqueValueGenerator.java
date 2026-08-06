package com.niv.payment.identity.oidc;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;
import java.util.function.Supplier;

public final class SecureOpaqueValueGenerator implements Supplier<String> {
    private static final int RANDOM_BYTES = 32;
    private final SecureRandom random;

    public SecureOpaqueValueGenerator(SecureRandom random) {
        this.random = Objects.requireNonNull(random, "random");
    }

    @Override
    public String get() {
        byte[] value = new byte[RANDOM_BYTES];
        random.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }
}
