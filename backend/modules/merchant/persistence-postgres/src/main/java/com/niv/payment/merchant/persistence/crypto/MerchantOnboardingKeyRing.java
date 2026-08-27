package com.niv.payment.merchant.persistence.crypto;

import com.niv.payment.merchant.core.MerchantException;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

public final class MerchantOnboardingKeyRing {
    private static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private final Map<String, SecretKey> legalIdKeys;
    private final Map<String, SecretKey> documentKeys;

    private MerchantOnboardingKeyRing(Map<String, SecretKey> legalIdKeys,
                                      Map<String, SecretKey> documentKeys) {
        this.legalIdKeys = Map.copyOf(legalIdKeys);
        this.documentKeys = Map.copyOf(documentKeys);
    }

    public static MerchantOnboardingKeyRing fromBase64(
        Map<String, String> legalIdKeys,
        Map<String, String> documentKeys,
        List<Map<String, String>> otherPurposeKeys
    ) {
        var all = new ArrayList<Map<String, byte[]>>();
        try {
            Map<String, byte[]> legal = decode(legalIdKeys);
            Map<String, byte[]> documents = decode(documentKeys);
            all.add(legal);
            all.add(documents);
            if (otherPurposeKeys != null) {
                otherPurposeKeys.forEach(keys -> all.add(decode(keys)));
            }
            rejectReuse(all);
            return new MerchantOnboardingKeyRing(secretKeys(legal), secretKeys(documents));
        } finally {
            all.forEach(values -> values.values().forEach(bytes -> Arrays.fill(bytes, (byte) 0)));
        }
    }

    SecretKey legalId(String keyId) { return require(legalIdKeys, keyId); }
    SecretKey document(String keyId) { return require(documentKeys, keyId); }

    private static Map<String, byte[]> decode(Map<String, String> configured) {
        if (configured == null || configured.isEmpty()) throw unavailable();
        var decoded = new java.util.LinkedHashMap<String, byte[]>();
        try {
            for (var entry : configured.entrySet()) {
                if (entry.getKey() == null || !KEY_ID.matcher(entry.getKey()).matches()
                    || entry.getValue() == null) throw unavailable();
                byte[] bytes = Base64.getDecoder().decode(entry.getValue());
                if (bytes.length != 32 || decoded.putIfAbsent(entry.getKey(), bytes) != null) {
                    Arrays.fill(bytes, (byte) 0);
                    throw unavailable();
                }
            }
            return decoded;
        } catch (RuntimeException exception) {
            decoded.values().forEach(bytes -> Arrays.fill(bytes, (byte) 0));
            throw unavailable();
        }
    }

    private static void rejectReuse(List<Map<String, byte[]>> purposes) {
        var ids = new java.util.HashSet<String>();
        var material = new ArrayList<byte[]>();
        for (var purpose : purposes) {
            for (var entry : purpose.entrySet()) {
                if (!ids.add(entry.getKey())
                    || material.stream().anyMatch(value -> MessageDigest.isEqual(value, entry.getValue()))) {
                    throw unavailable();
                }
                material.add(entry.getValue());
            }
        }
    }

    private static Map<String, SecretKey> secretKeys(Map<String, byte[]> values) {
        var keys = new java.util.LinkedHashMap<String, SecretKey>();
        values.forEach((id, bytes) -> keys.put(id, new SecretKeySpec(bytes, "AES")));
        return keys;
    }

    private static SecretKey require(Map<String, SecretKey> values, String keyId) {
        SecretKey key = values.get(Objects.requireNonNull(keyId, "keyId"));
        if (key == null) throw unavailable();
        return key;
    }

    private static MerchantException.ProtectedFieldUnavailable unavailable() {
        return new MerchantException.ProtectedFieldUnavailable();
    }
}
