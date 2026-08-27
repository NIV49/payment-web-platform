package com.niv.payment.merchant.persistence.crypto;

import com.niv.payment.merchant.core.MerchantException;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ThreePurposeKeyRingTest {
    private static final String SEARCH_KEY = base64(1);
    private static final String IDEMPOTENCY_KEY = base64(33);
    private static final String AEAD_KEY = base64(65);

    @Test
    void parsesRetainedVersionedKeysFromPropertiesAndEnvironment() {
        Properties properties = new Properties();
        properties.setProperty(ThreePurposeKeyRing.SEARCH_HMAC_KEYS_PROPERTY,
            "search-v1=" + SEARCH_KEY);
        properties.setProperty(ThreePurposeKeyRing.IDEMPOTENCY_HMAC_KEYS_PROPERTY,
            "idem-v1=" + IDEMPOTENCY_KEY);
        properties.setProperty(ThreePurposeKeyRing.REGISTRATION_AEAD_KEYS_PROPERTY,
            "aead-v1=" + AEAD_KEY);

        assertDoesNotThrow(() -> ThreePurposeKeyRing.fromProperties(properties));
        assertDoesNotThrow(() -> ThreePurposeKeyRing.fromEnvironment(Map.of(
            ThreePurposeKeyRing.SEARCH_HMAC_KEYS_ENV, "search-v1=" + SEARCH_KEY,
            ThreePurposeKeyRing.IDEMPOTENCY_HMAC_KEYS_ENV, "idem-v1=" + IDEMPOTENCY_KEY,
            ThreePurposeKeyRing.REGISTRATION_AEAD_KEYS_ENV, "aead-v1=" + AEAD_KEY
        )));
    }

    @Test
    void rejectsMissingMalformedWeakOrCrossPurposeReusedKeyMaterialWithoutLeakingIt() {
        assertThrows(MerchantException.ProtectedFieldUnavailable.class,
            () -> ThreePurposeKeyRing.fromBase64(Map.of(), Map.of("idem-v1", IDEMPOTENCY_KEY),
                Map.of("aead-v1", AEAD_KEY)));
        assertThrows(MerchantException.ProtectedFieldUnavailable.class,
            () -> ThreePurposeKeyRing.fromBase64(Map.of("search-v1", "not-base64!"),
                Map.of("idem-v1", IDEMPOTENCY_KEY), Map.of("aead-v1", AEAD_KEY)));
        assertThrows(MerchantException.ProtectedFieldUnavailable.class,
            () -> ThreePurposeKeyRing.fromBase64(Map.of("search-v1", base64Bytes(16)),
                Map.of("idem-v1", IDEMPOTENCY_KEY), Map.of("aead-v1", AEAD_KEY)));
        assertThrows(MerchantException.ProtectedFieldUnavailable.class,
            () -> ThreePurposeKeyRing.fromBase64(Map.of("search-v1", SEARCH_KEY),
                Map.of("idem-v1", SEARCH_KEY), Map.of("aead-v1", AEAD_KEY)));
        assertThrows(MerchantException.ProtectedFieldUnavailable.class,
            () -> ThreePurposeKeyRing.fromBase64(Map.of("shared-v1", SEARCH_KEY),
                Map.of("shared-v1", IDEMPOTENCY_KEY), Map.of("aead-v1", AEAD_KEY)));

        MerchantException.ProtectedFieldUnavailable failure = assertThrows(
            MerchantException.ProtectedFieldUnavailable.class,
            () -> ThreePurposeKeyRing.fromBase64(Map.of("search-v1", SEARCH_KEY),
                Map.of("idem-v1", IDEMPOTENCY_KEY), Map.of("aead-v1", "not-base64!"))
        );
        assertFalse(failure.toString().contains("not-base64"));
        assertFalse(failure.toString().contains(SEARCH_KEY));
    }

    private static String base64(int firstByte) {
        byte[] bytes = new byte[32];
        for (int index = 0; index < bytes.length; index++) {
            bytes[index] = (byte) (firstByte + index);
        }
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static String base64Bytes(int length) {
        return Base64.getEncoder().encodeToString(new byte[length]);
    }
}
