package com.niv.payment.merchant.persistence.crypto;

import com.niv.payment.merchant.core.MerchantException;
import com.niv.payment.merchant.core.RegistrationNumberPolicy;
import com.niv.payment.merchant.core.crypto.IdempotencyDigest;
import com.niv.payment.merchant.core.crypto.LengthFramedTupleCodec;
import com.niv.payment.merchant.core.crypto.MerchantCryptography;
import com.niv.payment.merchant.core.crypto.ProtectedRegistration;
import com.niv.payment.merchant.core.crypto.RegistrationFingerprint;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public final class JdkMerchantCryptography implements MerchantCryptography {
    public static final String HMAC_ALGORITHM = "HMAC-SHA-256";
    public static final String AEAD_ALGORITHM = "AES-256-GCM";
    public static final int FINGERPRINT_SCHEME_VERSION = 1;
    public static final int AAD_SCHEME_VERSION = 1;
    public static final int IDEMPOTENCY_DIGEST_SCHEME_VERSION = 1;

    private static final String JCA_HMAC_ALGORITHM = "HmacSHA256";
    private static final String JCA_AEAD_TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String REGISTRATION_FINGERPRINT_CONTEXT = "MCH-REG-v1";
    private static final String REGISTRATION_AEAD_CONTEXT = "MCH-REG-AEAD-v1";
    private static final String IDEMPOTENCY_CONTEXT = "MCH-IDEMP-v1";
    private static final String REGISTRATION_FIELD = "registrationNumber";
    private static final int NONCE_BYTES = 12;
    private static final int AUTHENTICATION_TAG_BITS = 128;
    private static final int AUTHENTICATION_TAG_BYTES = AUTHENTICATION_TAG_BITS / Byte.SIZE;

    private final ThreePurposeKeyRing keys;
    private final SecureRandom secureRandom;

    public JdkMerchantCryptography(ThreePurposeKeyRing keys) {
        this(keys, new SecureRandom());
    }

    JdkMerchantCryptography(ThreePurposeKeyRing keys, SecureRandom secureRandom) {
        this.keys = Objects.requireNonNull(keys, "keys");
        this.secureRandom = Objects.requireNonNull(secureRandom, "secureRandom");
    }

    @Override
    public RegistrationFingerprint fingerprint(
        String searchKeyId,
        int normalizationVersion,
        String country,
        String registrationNumber
    ) {
        requireNormalizationVersion(normalizationVersion);
        String validatedCountry = RegistrationNumberPolicy.country(country);
        String normalized = RegistrationNumberPolicy.normalize(registrationNumber);
        return fingerprintNormalized(searchKeyId, validatedCountry, normalized);
    }

    @Override
    public ProtectedRegistration protect(
        long merchantId,
        long tenantId,
        String country,
        String registrationNumber,
        String searchKeyId,
        String aeadKeyId
    ) {
        requirePositiveIds(merchantId, tenantId);
        String validatedCountry = RegistrationNumberPolicy.country(country);
        String normalized = RegistrationNumberPolicy.normalize(registrationNumber);
        RegistrationFingerprint fingerprint = fingerprintNormalized(
            searchKeyId, validatedCountry, normalized);
        SecretKey aeadKey = keys.registrationAead(aeadKeyId);
        byte[] nonce = new byte[NONCE_BYTES];
        secureRandom.nextBytes(nonce);
        byte[] encrypted = encrypt(
            aeadKey,
            nonce,
            aad(merchantId, tenantId, validatedCountry,
                RegistrationNumberPolicy.NORMALIZATION_VERSION, AEAD_ALGORITHM, aeadKeyId),
            normalized.getBytes(StandardCharsets.UTF_8)
        );
        int ciphertextLength = encrypted.length - AUTHENTICATION_TAG_BYTES;
        if (ciphertextLength < 1) throw unavailable();
        return new ProtectedRegistration(
            AAD_SCHEME_VERSION,
            RegistrationNumberPolicy.NORMALIZATION_VERSION,
            AEAD_ALGORITHM,
            aeadKeyId,
            nonce,
            Arrays.copyOfRange(encrypted, 0, ciphertextLength),
            Arrays.copyOfRange(encrypted, ciphertextLength, encrypted.length),
            RegistrationNumberPolicy.mask(normalized),
            fingerprint
        );
    }

    @Override
    public String decryptNormalized(
        long merchantId,
        long tenantId,
        String country,
        ProtectedRegistration protectedRegistration
    ) {
        requirePositiveIds(merchantId, tenantId);
        if (protectedRegistration == null
            || protectedRegistration.aadSchemeVersion() != AAD_SCHEME_VERSION
            || protectedRegistration.normalizationVersion() != RegistrationNumberPolicy.NORMALIZATION_VERSION
            || !AEAD_ALGORITHM.equals(protectedRegistration.algorithm())
            || protectedRegistration.nonce().length != NONCE_BYTES
            || protectedRegistration.authenticationTag().length != AUTHENTICATION_TAG_BYTES
            || protectedRegistration.ciphertext().length < 1) {
            throw unavailable();
        }
        String validatedCountry;
        try {
            validatedCountry = RegistrationNumberPolicy.country(country);
        } catch (MerchantException.InvalidRequest exception) {
            throw unavailable();
        }
        SecretKey aeadKey = keys.registrationAead(protectedRegistration.aeadKeyId());
        byte[] encrypted = ByteBuffer.allocate(
                protectedRegistration.ciphertext().length + AUTHENTICATION_TAG_BYTES)
            .put(protectedRegistration.ciphertext())
            .put(protectedRegistration.authenticationTag())
            .array();
        byte[] plaintext = decrypt(
            aeadKey,
            protectedRegistration.nonce(),
            aad(merchantId, tenantId, validatedCountry,
                protectedRegistration.normalizationVersion(), protectedRegistration.algorithm(),
                protectedRegistration.aeadKeyId()),
            encrypted
        );
        String normalized = decodeUtf8(plaintext);
        try {
            if (!normalized.equals(RegistrationNumberPolicy.normalize(normalized))) throw unavailable();
        } catch (MerchantException.InvalidRequest exception) {
            throw unavailable();
        }
        return normalized;
    }

    @Override
    public IdempotencyDigest idempotencyDigest(
        String keyId,
        int digestSchemeVersion,
        List<String> normalizedCommandFields
    ) {
        if (digestSchemeVersion != IDEMPOTENCY_DIGEST_SCHEME_VERSION
            || normalizedCommandFields == null) {
            throw unavailable();
        }
        String[] framedFields = new String[normalizedCommandFields.size() + 1];
        framedFields[0] = IDEMPOTENCY_CONTEXT;
        for (int index = 0; index < normalizedCommandFields.size(); index++) {
            framedFields[index + 1] = normalizedCommandFields.get(index);
        }
        return new IdempotencyDigest(
            digestSchemeVersion,
            HMAC_ALGORITHM,
            keyId,
            hmac(keys.idempotencyHmac(keyId),
                LengthFramedTupleCodec.encode(LengthFramedTupleCodec.VERSION_1, framedFields))
        );
    }

    private RegistrationFingerprint fingerprintNormalized(
        String searchKeyId,
        String country,
        String normalized
    ) {
        byte[] framed = LengthFramedTupleCodec.encode(
            LengthFramedTupleCodec.VERSION_1,
            REGISTRATION_FINGERPRINT_CONTEXT,
            country,
            normalized
        );
        return new RegistrationFingerprint(
            FINGERPRINT_SCHEME_VERSION,
            RegistrationNumberPolicy.NORMALIZATION_VERSION,
            HMAC_ALGORITHM,
            searchKeyId,
            hmac(keys.searchHmac(searchKeyId), framed)
        );
    }

    private static byte[] aad(
        long merchantId,
        long tenantId,
        String country,
        int normalizationVersion,
        String algorithm,
        String keyId
    ) {
        return LengthFramedTupleCodec.encode(
            LengthFramedTupleCodec.VERSION_1,
            REGISTRATION_AEAD_CONTEXT,
            Long.toString(merchantId),
            Long.toString(tenantId),
            country,
            REGISTRATION_FIELD,
            Integer.toString(normalizationVersion),
            algorithm,
            keyId
        );
    }

    private static byte[] hmac(SecretKey key, byte[] input) {
        try {
            Mac mac = Mac.getInstance(JCA_HMAC_ALGORITHM);
            mac.init(key);
            return mac.doFinal(input);
        } catch (GeneralSecurityException exception) {
            throw unavailable();
        }
    }

    private static byte[] encrypt(SecretKey key, byte[] nonce, byte[] aad, byte[] plaintext) {
        try {
            Cipher cipher = Cipher.getInstance(JCA_AEAD_TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(AUTHENTICATION_TAG_BITS, nonce));
            cipher.updateAAD(aad);
            return cipher.doFinal(plaintext);
        } catch (GeneralSecurityException exception) {
            throw unavailable();
        }
    }

    private static byte[] decrypt(SecretKey key, byte[] nonce, byte[] aad, byte[] encrypted) {
        try {
            Cipher cipher = Cipher.getInstance(JCA_AEAD_TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(AUTHENTICATION_TAG_BITS, nonce));
            cipher.updateAAD(aad);
            return cipher.doFinal(encrypted);
        } catch (GeneralSecurityException exception) {
            throw unavailable();
        }
    }

    private static String decodeUtf8(byte[] value) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(value))
                .toString();
        } catch (CharacterCodingException exception) {
            throw unavailable();
        }
    }

    private static void requireNormalizationVersion(int normalizationVersion) {
        if (normalizationVersion != RegistrationNumberPolicy.NORMALIZATION_VERSION) {
            throw unavailable();
        }
    }

    private static void requirePositiveIds(long merchantId, long tenantId) {
        if (merchantId <= 0 || tenantId <= 0) throw unavailable();
    }

    private static MerchantException.ProtectedFieldUnavailable unavailable() {
        return new MerchantException.ProtectedFieldUnavailable();
    }
}
