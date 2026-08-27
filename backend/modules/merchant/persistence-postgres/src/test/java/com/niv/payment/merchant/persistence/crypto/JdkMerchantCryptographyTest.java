package com.niv.payment.merchant.persistence.crypto;

import com.niv.payment.merchant.core.MerchantException;
import com.niv.payment.merchant.core.crypto.IdempotencyDigest;
import com.niv.payment.merchant.core.crypto.ProtectedRegistration;
import com.niv.payment.merchant.core.crypto.RegistrationFingerprint;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JdkMerchantCryptographyTest {
    private static final String SEARCH_KEY_ID = "search-v1";
    private static final String IDEMPOTENCY_KEY_ID = "idem-v1";
    private static final String AEAD_KEY_ID = "aead-v1";

    @Test
    void normalizesAndFingerprintsCountryPlusRegistrationWithSearchHmacOnly() {
        JdkMerchantCryptography crypto = cryptography();

        RegistrationFingerprint first = crypto.fingerprint(
            SEARCH_KEY_ID, 1, "SG", "  2026-001234-z ");
        RegistrationFingerprint equivalent = crypto.fingerprint(
            SEARCH_KEY_ID, 1, "SG", "2026001234Z");
        RegistrationFingerprint otherCountry = crypto.fingerprint(
            SEARCH_KEY_ID, 1, "US", "2026001234Z");

        assertArrayEquals(first.value(), equivalent.value());
        assertFalse(first.matches(otherCountry));
        assertEquals("HMAC-SHA-256", first.algorithm());
        assertEquals(1, first.schemeVersion());
        assertEquals(32, first.value().length);
        assertThrows(MerchantException.ProtectedFieldUnavailable.class,
            () -> crypto.fingerprint(IDEMPOTENCY_KEY_ID, 1, "SG", "2026001234Z"));
    }

    @Test
    void encryptsNormalizedRegistrationWithSeparatedCiphertextTagAndBoundAad() {
        JdkMerchantCryptography crypto = cryptography();

        ProtectedRegistration protectedValue = crypto.protect(
            701L, 901L, "SG", "  2026-001234-z ", SEARCH_KEY_ID, AEAD_KEY_ID);
        ProtectedRegistration secondValue = crypto.protect(
            701L, 901L, "SG", "  2026-001234-z ", SEARCH_KEY_ID, AEAD_KEY_ID);

        assertEquals("2026001234Z",
            crypto.decryptNormalized(701L, 901L, "SG", protectedValue));
        assertEquals("*******234Z", protectedValue.masked());
        assertEquals(12, protectedValue.nonce().length);
        assertEquals(16, protectedValue.authenticationTag().length);
        assertEquals("2026001234Z".getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
            protectedValue.ciphertext().length);
        assertEquals("AES-256-GCM", protectedValue.algorithm());
        assertEquals(1, protectedValue.aadSchemeVersion());
        assertNotEquals(Base64.getEncoder().encodeToString(protectedValue.nonce()),
            Base64.getEncoder().encodeToString(secondValue.nonce()));
        assertNotEquals(Base64.getEncoder().encodeToString(protectedValue.ciphertext()),
            Base64.getEncoder().encodeToString(secondValue.ciphertext()));
        assertThrows(MerchantException.ProtectedFieldUnavailable.class,
            () -> crypto.protect(0L, 901L, "SG", "2026001234Z", SEARCH_KEY_ID, AEAD_KEY_ID));
    }

    @Test
    void rejectsCiphertextTagAndEveryAuthenticatedContextSwap() {
        JdkMerchantCryptography crypto = cryptography();
        ProtectedRegistration value = crypto.protect(
            701L, 901L, "SG", "2026001234Z", SEARCH_KEY_ID, AEAD_KEY_ID);

        assertUnavailable(() -> crypto.decryptNormalized(702L, 901L, "SG", value));
        assertUnavailable(() -> crypto.decryptNormalized(701L, 902L, "SG", value));
        assertUnavailable(() -> crypto.decryptNormalized(701L, 901L, "US", value));

        byte[] changedCiphertext = value.ciphertext();
        changedCiphertext[0] ^= 1;
        assertUnavailable(() -> crypto.decryptNormalized(701L, 901L, "SG",
            copy(value, value.algorithm(), value.aeadKeyId(), changedCiphertext,
                value.authenticationTag(), value.normalizationVersion())));

        byte[] changedTag = value.authenticationTag();
        changedTag[0] ^= 1;
        assertUnavailable(() -> crypto.decryptNormalized(701L, 901L, "SG",
            copy(value, value.algorithm(), value.aeadKeyId(), value.ciphertext(),
                changedTag, value.normalizationVersion())));

        assertUnavailable(() -> crypto.decryptNormalized(701L, 901L, "SG",
            copy(value, "AES-128-GCM", value.aeadKeyId(), value.ciphertext(),
                value.authenticationTag(), value.normalizationVersion())));
        assertUnavailable(() -> crypto.decryptNormalized(701L, 901L, "SG",
            copy(value, value.algorithm(), "missing-aead", value.ciphertext(),
                value.authenticationTag(), value.normalizationVersion())));
        assertUnavailable(() -> crypto.decryptNormalized(701L, 901L, "SG",
            copy(value, value.algorithm(), value.aeadKeyId(), value.ciphertext(),
                value.authenticationTag(), 2)));
    }

    @Test
    void protectedValuesDefensivelyCopyArraysAndRedactTheirStringForms() {
        JdkMerchantCryptography crypto = cryptography();
        ProtectedRegistration value = crypto.protect(
            701L, 901L, "SG", "2026001234Z", SEARCH_KEY_ID, AEAD_KEY_ID);
        byte[] nonce = value.nonce();
        byte original = nonce[0];
        nonce[0] ^= 1;

        assertEquals(original, value.nonce()[0]);
        assertFalse(value.toString().contains("2026001234Z"));
        assertFalse(value.fingerprint().toString().contains(
            Base64.getEncoder().encodeToString(value.fingerprint().value())));
    }

    @Test
    void computesPermanentVersionedIdempotencyDigestWithoutTupleAmbiguity() {
        JdkMerchantCryptography crypto = cryptography();

        IdempotencyDigest first = crypto.idempotencyDigest(
            IDEMPOTENCY_KEY_ID, 1, List.of("SUBMIT", "A", "BC", "2026001234Z"));
        IdempotencyDigest same = crypto.idempotencyDigest(
            IDEMPOTENCY_KEY_ID, 1, List.of("SUBMIT", "A", "BC", "2026001234Z"));
        IdempotencyDigest differentBoundary = crypto.idempotencyDigest(
            IDEMPOTENCY_KEY_ID, 1, List.of("SUBMIT", "AB", "C", "2026001234Z"));

        assertArrayEquals(first.value(), same.value());
        assertFalse(first.matches(differentBoundary));
        assertEquals(32, first.value().length);
        assertEquals("HMAC-SHA-256", first.algorithm());
        assertThrows(MerchantException.ProtectedFieldUnavailable.class,
            () -> crypto.idempotencyDigest(SEARCH_KEY_ID, 1, List.of("SUBMIT")));
        assertThrows(MerchantException.ProtectedFieldUnavailable.class,
            () -> crypto.idempotencyDigest("missing", 1, List.of("SUBMIT")));
        assertThrows(MerchantException.ProtectedFieldUnavailable.class,
            () -> crypto.idempotencyDigest(IDEMPOTENCY_KEY_ID, 2, List.of("SUBMIT")));
    }

    private static JdkMerchantCryptography cryptography() {
        ThreePurposeKeyRing keyRing = ThreePurposeKeyRing.fromBase64(
            Map.of(SEARCH_KEY_ID, base64(1)),
            Map.of(IDEMPOTENCY_KEY_ID, base64(33)),
            Map.of(AEAD_KEY_ID, base64(65))
        );
        return new JdkMerchantCryptography(keyRing, new IncrementingSecureRandom());
    }

    private static ProtectedRegistration copy(
        ProtectedRegistration original,
        String algorithm,
        String keyId,
        byte[] ciphertext,
        byte[] tag,
        int normalizationVersion
    ) {
        return new ProtectedRegistration(
            original.aadSchemeVersion(), normalizationVersion, algorithm, keyId,
            original.nonce(), ciphertext, tag, original.masked(), original.fingerprint()
        );
    }

    private static void assertUnavailable(Runnable action) {
        assertThrows(MerchantException.ProtectedFieldUnavailable.class, action::run);
    }

    private static String base64(int firstByte) {
        byte[] bytes = new byte[32];
        for (int index = 0; index < bytes.length; index++) {
            bytes[index] = (byte) (firstByte + index);
        }
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static final class IncrementingSecureRandom extends SecureRandom {
        private int next;

        @Override
        public void nextBytes(byte[] bytes) {
            for (int index = 0; index < bytes.length; index++) {
                bytes[index] = (byte) next++;
            }
        }
    }
}
