package com.niv.payment.merchant.core;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;

public final class RegistrationNumberPolicy {
    public static final int NORMALIZATION_VERSION = 1;
    private static final Set<String> COUNTRIES = Set.of(Locale.getISOCountries());

    private RegistrationNumberPolicy() {
    }

    public static String country(String value) {
        if (value == null || !value.matches("[A-Z]{2}") || !COUNTRIES.contains(value)) {
            throw new MerchantException.InvalidRequest("registrationCountry must be an assigned ISO alpha-2 code");
        }
        return value;
    }

    public static String normalize(String value) {
        if (value == null) throw new MerchantException.InvalidRequest("registrationNumber is required");
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC);
        StringBuilder compact = new StringBuilder(normalized.length());
        normalized.codePoints().forEach(codePoint -> {
            if (!Character.isWhitespace(codePoint) && !Character.isSpaceChar(codePoint)
                && codePoint != '-' && codePoint != '.' && codePoint != '/') {
                compact.appendCodePoint(codePoint);
            }
        });
        String result = compact.toString().toUpperCase(Locale.ROOT);
        int length = result.codePointCount(0, result.length());
        if (length < 1 || length > 128) {
            throw new MerchantException.InvalidRequest("registrationNumber must normalize to 1..128 code points");
        }
        return result;
    }

    public static String mask(String normalized) {
        if (normalized == null) throw new MerchantException.InvalidRequest("registrationNumber is required");
        int count = normalized.codePointCount(0, normalized.length());
        int visible = Math.min(4, count);
        if (count <= 4) return "*".repeat(count);
        int suffixOffset = normalized.offsetByCodePoints(0, count - visible);
        return "*".repeat(count - visible) + normalized.substring(suffixOffset);
    }
}
