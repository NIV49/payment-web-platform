package com.niv.payment.merchant.persistence.crypto;

import com.niv.payment.merchant.core.MerchantException;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;
import java.security.MessageDigest;

public final class ThreePurposeKeyRing {
    public static final String SEARCH_HMAC_KEYS_PROPERTY = "merchant.protection.search-hmac-keys";
    public static final String IDEMPOTENCY_HMAC_KEYS_PROPERTY = "merchant.protection.idempotency-hmac-keys";
    public static final String REGISTRATION_AEAD_KEYS_PROPERTY = "merchant.protection.registration-aead-keys";
    public static final String SEARCH_HMAC_KEYS_ENV = "MCH_SEARCH_HMAC_KEYS";
    public static final String IDEMPOTENCY_HMAC_KEYS_ENV = "MCH_IDEMPOTENCY_HMAC_KEYS";
    public static final String REGISTRATION_AEAD_KEYS_ENV = "MCH_REGISTRATION_AEAD_KEYS";

    private static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private static final int KEY_BYTES = 32;

    private final Map<String, SecretKey> searchHmacKeys;
    private final Map<String, SecretKey> idempotencyHmacKeys;
    private final Map<String, SecretKey> registrationAeadKeys;

    private ThreePurposeKeyRing(
        Map<String, SecretKey> searchHmacKeys,
        Map<String, SecretKey> idempotencyHmacKeys,
        Map<String, SecretKey> registrationAeadKeys
    ) {
        this.searchHmacKeys = Map.copyOf(searchHmacKeys);
        this.idempotencyHmacKeys = Map.copyOf(idempotencyHmacKeys);
        this.registrationAeadKeys = Map.copyOf(registrationAeadKeys);
    }

    public static ThreePurposeKeyRing fromProperties(Properties properties) {
        if (properties == null) throw unavailable();
        return fromBase64(
            parseEntries(properties.getProperty(SEARCH_HMAC_KEYS_PROPERTY)),
            parseEntries(properties.getProperty(IDEMPOTENCY_HMAC_KEYS_PROPERTY)),
            parseEntries(properties.getProperty(REGISTRATION_AEAD_KEYS_PROPERTY))
        );
    }

    public static ThreePurposeKeyRing fromEnvironment(Map<String, String> environment) {
        if (environment == null) throw unavailable();
        return fromBase64(
            parseEntries(environment.get(SEARCH_HMAC_KEYS_ENV)),
            parseEntries(environment.get(IDEMPOTENCY_HMAC_KEYS_ENV)),
            parseEntries(environment.get(REGISTRATION_AEAD_KEYS_ENV))
        );
    }

    public static ThreePurposeKeyRing fromBase64(
        Map<String, String> searchHmacKeys,
        Map<String, String> idempotencyHmacKeys,
        Map<String, String> registrationAeadKeys
    ) {
        Map<String, byte[]> search = new LinkedHashMap<>();
        Map<String, byte[]> idempotency = new LinkedHashMap<>();
        Map<String, byte[]> aead = new LinkedHashMap<>();
        try {
            search.putAll(decode(searchHmacKeys));
            idempotency.putAll(decode(idempotencyHmacKeys));
            aead.putAll(decode(registrationAeadKeys));
            rejectCrossPurposeKeyIds(search, idempotency, aead);
            rejectCrossPurposeReuse(search, idempotency, aead);
            return new ThreePurposeKeyRing(
                secretKeys(search, "HmacSHA256"),
                secretKeys(idempotency, "HmacSHA256"),
                secretKeys(aead, "AES")
            );
        } finally {
            wipe(search, idempotency, aead);
        }
    }

    SecretKey searchHmac(String keyId) {
        return require(searchHmacKeys, keyId);
    }

    SecretKey idempotencyHmac(String keyId) {
        return require(idempotencyHmacKeys, keyId);
    }

    SecretKey registrationAead(String keyId) {
        return require(registrationAeadKeys, keyId);
    }

    private static Map<String, String> parseEntries(String configured) {
        if (configured == null || configured.isBlank()) throw unavailable();
        Map<String, String> entries = new LinkedHashMap<>();
        for (String entry : configured.split(",", -1)) {
            int separator = entry.indexOf('=');
            if (separator <= 0 || separator == entry.length() - 1) throw unavailable();
            String keyId = entry.substring(0, separator).trim();
            String encodedKey = entry.substring(separator + 1).trim();
            if (entries.putIfAbsent(keyId, encodedKey) != null) throw unavailable();
        }
        return entries;
    }

    private static Map<String, byte[]> decode(Map<String, String> encodedKeys) {
        if (encodedKeys == null || encodedKeys.isEmpty()) throw unavailable();
        Map<String, byte[]> decoded = new LinkedHashMap<>();
        try {
            for (Map.Entry<String, String> entry : encodedKeys.entrySet()) {
                if (entry.getKey() == null || !KEY_ID.matcher(entry.getKey()).matches()
                    || entry.getValue() == null) {
                    throw unavailable();
                }
                byte[] material = Base64.getDecoder().decode(entry.getValue());
                if (material.length != KEY_BYTES) {
                    Arrays.fill(material, (byte) 0);
                    throw unavailable();
                }
                decoded.put(entry.getKey(), material);
            }
            return decoded;
        } catch (RuntimeException exception) {
            wipe(decoded);
            throw unavailable();
        }
    }

    @SafeVarargs
    private static void rejectCrossPurposeKeyIds(Map<String, byte[]>... purposes) {
        Set<String> keyIds = new HashSet<>();
        for (Map<String, byte[]> purpose : purposes) {
            for (String keyId : purpose.keySet()) {
                if (!keyIds.add(keyId)) throw unavailable();
            }
        }
    }

    @SafeVarargs
    private static void rejectCrossPurposeReuse(Map<String, byte[]>... purposes) {
        List<byte[]> observed = new ArrayList<>();
        for (Map<String, byte[]> purpose : purposes) {
            for (byte[] material : purpose.values()) {
                if (observed.stream().anyMatch(previous -> MessageDigest.isEqual(previous, material))) {
                    throw unavailable();
                }
                observed.add(material);
            }
        }
    }

    @SafeVarargs
    private static void wipe(Map<String, byte[]>... purposes) {
        for (Map<String, byte[]> purpose : purposes) {
            purpose.values().forEach(material -> Arrays.fill(material, (byte) 0));
        }
    }

    private static Map<String, SecretKey> secretKeys(Map<String, byte[]> decoded, String algorithm) {
        Map<String, SecretKey> keys = new LinkedHashMap<>();
        decoded.forEach((keyId, material) -> keys.put(keyId, new SecretKeySpec(material, algorithm)));
        return keys;
    }

    private static SecretKey require(Map<String, SecretKey> keys, String keyId) {
        if (keyId == null) throw unavailable();
        SecretKey key = keys.get(keyId);
        if (key == null) throw unavailable();
        return key;
    }

    private static MerchantException.ProtectedFieldUnavailable unavailable() {
        return new MerchantException.ProtectedFieldUnavailable();
    }
}
