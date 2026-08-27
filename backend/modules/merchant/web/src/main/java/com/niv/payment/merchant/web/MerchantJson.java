package com.niv.payment.merchant.web;

import com.niv.payment.merchant.core.MerchantException;
import tools.jackson.databind.JsonNode;

import java.util.Set;
import java.util.List;
import java.util.UUID;

final class MerchantJson {
    private MerchantJson() { }

    static void exactObject(JsonNode node, Set<String> allowed) {
        if (node == null || !node.isObject() || !allowed.containsAll(node.propertyNames())) {
            throw invalid();
        }
    }

    static void exactRequiredObject(JsonNode node, Set<String> required) {
        if (node == null || !node.isObject() || !required.equals(Set.copyOf(node.propertyNames()))) {
            throw invalid();
        }
        for (String field : required) if (node.get(field) == null || node.get(field).isNull()) throw invalid();
    }

    static String requiredString(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isString()) throw invalid();
        return value.stringValue();
    }

    static String optionalString(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null) return null;
        if (!value.isString()) throw invalid();
        return value.stringValue();
    }

    static UUID uuid(JsonNode node, String field) {
        try {
            String source = requiredString(node, field);
            UUID parsed = UUID.fromString(source);
            if (!parsed.toString().equalsIgnoreCase(source)) throw invalid();
            return parsed;
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }
    }

    static List<String> requiredStringList(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isArray()) throw invalid();
        java.util.ArrayList<String> result = new java.util.ArrayList<>();
        for (JsonNode item : value) {
            if (!item.isString()) throw invalid();
            result.add(item.stringValue());
        }
        return List.copyOf(result);
    }

    static long nonNegativeLong(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) throw invalid();
        long result = value.longValue();
        if (result < 0) throw invalid();
        return result;
    }

    static long positiveLongString(JsonNode node, String field) {
        String value = requiredString(node, field);
        if (!value.matches("[1-9][0-9]{0,18}")) throw invalid();
        try {
            long result = Long.parseLong(value);
            if (result <= 0) throw invalid();
            return result;
        } catch (NumberFormatException exception) {
            throw invalid();
        }
    }

    static Long nullableRequiredNonNegativeLong(JsonNode node, String field) {
        if (!node.has(field)) throw invalid();
        JsonNode value = node.get(field);
        if (value.isNull()) return null;
        if (!value.isIntegralNumber() || !value.canConvertToLong()) throw invalid();
        long result = value.longValue();
        if (result < 0) throw invalid();
        return result;
    }

    static MerchantException.InvalidRequest invalid() {
        return new MerchantException.InvalidRequest("Invalid request");
    }
}
