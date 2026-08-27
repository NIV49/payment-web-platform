package com.niv.payment.merchant.persistence.crypto;

import com.niv.payment.merchant.core.MerchantException;
import com.niv.payment.merchant.core.MerchantOnboardingModels.DocumentKind;
import com.niv.payment.merchant.core.RegistrationNumberPolicy;
import com.niv.payment.merchant.core.crypto.LengthFramedTupleCodec;
import com.niv.payment.merchant.core.crypto.MerchantOnboardingCryptography;
import com.niv.payment.merchant.core.crypto.ProtectedPayload;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;

public final class JdkMerchantOnboardingCryptography implements MerchantOnboardingCryptography {
    public static final String ALGORITHM = "AES-256-GCM";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BYTES = 16;
    private final MerchantOnboardingKeyRing keys;
    private final SecureRandom random;

    public JdkMerchantOnboardingCryptography(MerchantOnboardingKeyRing keys) {
        this(keys, new SecureRandom());
    }

    JdkMerchantOnboardingCryptography(MerchantOnboardingKeyRing keys, SecureRandom random) {
        this.keys = Objects.requireNonNull(keys, "keys");
        this.random = Objects.requireNonNull(random, "random");
    }

    @Override
    public ProtectedPayload protectLegalId(long merchantId, long tenantId, String legalIdType,
                                             String plaintext, String keyId) {
        if (merchantId <= 0 || tenantId <= 0 || legalIdType == null) throw unavailable();
        String normalized = RegistrationNumberPolicy.normalize(plaintext);
        return protect(keys.legalId(keyId), keyId,
            aad("MCH-LEGAL-ID-v1", merchantId, tenantId, legalIdType, "legalIdNo",
                1, 1, ALGORITHM, keyId),
            normalized.getBytes(StandardCharsets.UTF_8), RegistrationNumberPolicy.mask(normalized));
    }

    @Override
    public String decryptLegalId(long merchantId, long tenantId, String legalIdType,
                                 ProtectedPayload payload) {
        if (merchantId <= 0 || tenantId <= 0 || legalIdType == null || payload == null
            || payload.aadSchemeVersion() != 1 || payload.protectionVersion() != 1
            || !ALGORITHM.equals(payload.algorithm()) || payload.nonce().length != NONCE_BYTES
            || payload.authenticationTag().length != TAG_BYTES) throw unavailable();
        byte[] plaintext = decrypt(keys.legalId(payload.keyId()), payload,
            aad("MCH-LEGAL-ID-v1", merchantId, tenantId, legalIdType, "legalIdNo",
                payload.protectionVersion(), payload.aadSchemeVersion(), payload.algorithm(),
                payload.keyId()));
        try {
            String normalized = new String(plaintext, StandardCharsets.UTF_8);
            if (!normalized.equals(RegistrationNumberPolicy.normalize(normalized))
                || !Objects.equals(payload.masked(), RegistrationNumberPolicy.mask(normalized))) {
                throw unavailable();
            }
            return normalized;
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    @Override
    public ProtectedPayload protectDocument(long documentId, long targetTenantId,
                                              long actorMembershipId, DocumentKind kind,
                                              String mediaType, int width, int height, int size,
                                              byte[] content, String keyId) {
        validateDocument(documentId, targetTenantId, actorMembershipId, kind, mediaType,
            width, height, size, content);
        return protect(keys.document(keyId), keyId,
            documentAad(documentId, targetTenantId, actorMembershipId, kind, mediaType,
                width, height, size, keyId), content, null);
    }

    @Override
    public byte[] decryptDocument(long documentId, long targetTenantId, long actorMembershipId,
                                  DocumentKind kind, String mediaType, int width, int height, int size,
                                  ProtectedPayload payload) {
        validateDocument(documentId, targetTenantId, actorMembershipId, kind, mediaType,
            width, height, size, new byte[size]);
        if (payload == null || payload.aadSchemeVersion() != 1 || payload.protectionVersion() != 1
            || !ALGORITHM.equals(payload.algorithm()) || payload.nonce().length != NONCE_BYTES
            || payload.authenticationTag().length != TAG_BYTES) throw unavailable();
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, keys.document(payload.keyId()),
                new GCMParameterSpec(128, payload.nonce()));
            cipher.updateAAD(documentAad(documentId, targetTenantId, actorMembershipId, kind,
                mediaType, width, height, size, payload.keyId()));
            byte[] joined = new byte[payload.ciphertext().length + TAG_BYTES];
            System.arraycopy(payload.ciphertext(), 0, joined, 0, payload.ciphertext().length);
            System.arraycopy(payload.authenticationTag(), 0, joined, payload.ciphertext().length, TAG_BYTES);
            byte[] result = cipher.doFinal(joined);
            Arrays.fill(joined, (byte) 0);
            if (result.length != size) throw unavailable();
            return result;
        } catch (GeneralSecurityException exception) {
            throw new MerchantException.ProtectedFieldUnavailable(exception);
        }
    }

    private ProtectedPayload protect(SecretKey key, String keyId, byte[] aad,
                                       byte[] plaintext, String masked) {
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            cipher.updateAAD(aad);
            byte[] encrypted = cipher.doFinal(plaintext);
            int split = encrypted.length - TAG_BYTES;
            return new ProtectedPayload(1, 1, ALGORITHM, keyId, nonce,
                Arrays.copyOfRange(encrypted, 0, split),
                Arrays.copyOfRange(encrypted, split, encrypted.length), masked);
        } catch (GeneralSecurityException exception) {
            throw new MerchantException.ProtectedFieldUnavailable(exception);
        }
    }

    private static byte[] decrypt(SecretKey key, ProtectedPayload payload, byte[] aad) {
        byte[] joined = new byte[payload.ciphertext().length + TAG_BYTES];
        try {
            System.arraycopy(payload.ciphertext(), 0, joined, 0, payload.ciphertext().length);
            System.arraycopy(payload.authenticationTag(), 0, joined,
                payload.ciphertext().length, TAG_BYTES);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, payload.nonce()));
            cipher.updateAAD(aad);
            return cipher.doFinal(joined);
        } catch (GeneralSecurityException exception) {
            throw new MerchantException.ProtectedFieldUnavailable(exception);
        } finally {
            Arrays.fill(joined, (byte) 0);
        }
    }

    private static byte[] documentAad(long documentId, long targetTenantId,
                                      long actorMembershipId, DocumentKind kind,
                                      String mediaType, int width, int height, int size,
                                      String keyId) {
        return aad("MCH-DOCUMENT-v1", documentId, targetTenantId, actorMembershipId,
            kind.name(), mediaType, width, height, size, 1, ALGORITHM, keyId);
    }

    private static byte[] aad(String context, Object... values) {
        String[] fields = new String[values.length + 1];
        fields[0] = context;
        for (int index = 0; index < values.length; index++) fields[index + 1] = String.valueOf(values[index]);
        return LengthFramedTupleCodec.encode(1, fields);
    }

    private static void validateDocument(long documentId, long targetTenantId,
                                         long actorMembershipId, DocumentKind kind,
                                         String mediaType, int width, int height, int size,
                                         byte[] content) {
        if (documentId <= 0 || targetTenantId <= 0 || actorMembershipId <= 0 || kind == null
            || !("image/png".equals(mediaType) || "image/jpeg".equals(mediaType))
            || width < 1 || width > 4096 || height < 1 || height > 4096
            || (long) width * height > 12_000_000L || size < 1 || size > 2 * 1024 * 1024
            || content == null || content.length != size) throw unavailable();
    }

    private static MerchantException.ProtectedFieldUnavailable unavailable() {
        return new MerchantException.ProtectedFieldUnavailable();
    }
}
