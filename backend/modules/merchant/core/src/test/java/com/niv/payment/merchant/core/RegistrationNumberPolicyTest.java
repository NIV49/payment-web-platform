package com.niv.payment.merchant.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RegistrationNumberPolicyTest {
    @Test
    void normalizesUsingTheVersionOneContract() {
        assertEquals("ABC123Z", RegistrationNumberPolicy.normalize("  ａ-b c/123.Z  "));
        assertEquals("AB", RegistrationNumberPolicy.normalize("A\u00a0\u202fB"));
        assertEquals(1, RegistrationNumberPolicy.NORMALIZATION_VERSION);
    }

    @Test
    void validatesAssignedUppercaseCountryAndBounds() {
        assertEquals("SG", RegistrationNumberPolicy.country("SG"));
        assertThrows(MerchantException.InvalidRequest.class,
            () -> RegistrationNumberPolicy.country("sg"));
        assertThrows(MerchantException.InvalidRequest.class,
            () -> RegistrationNumberPolicy.country("ZZ"));
        assertThrows(MerchantException.InvalidRequest.class,
            () -> RegistrationNumberPolicy.normalize(" / - . \t"));
        assertThrows(MerchantException.InvalidRequest.class,
            () -> RegistrationNumberPolicy.normalize("A".repeat(129)));
    }

    @Test
    void masksAllShortValuesAndExposesAtMostFourTrailingCodePoints() {
        assertEquals("****", RegistrationNumberPolicy.mask("AB12"));
        assertEquals("**1234", RegistrationNumberPolicy.mask("AB1234"));
        assertEquals("***1234", RegistrationNumberPolicy.mask("AB😀1234"));
    }
}
