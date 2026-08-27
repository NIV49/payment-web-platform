package com.niv.payment.dictionary.cache;

import com.niv.payment.dictionary.core.DictionaryBatchCache;
import com.niv.payment.dictionary.core.DictionaryModels;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.HashSet;

public final class RedisDictionaryBatchCache implements DictionaryBatchCache {
    private static final String PREFIX = "dictionary:catalog:v";
    private static final int MAX_VALUES_PER_TYPE = 1_000;
    private static final Set<String> COLORS = Set.of(
        "default", "processing", "success", "warning", "error", "purple");

    private final DictionaryRedisStore store;
    private final ObjectMapper json;
    private final Duration ttl;

    public RedisDictionaryBatchCache(DictionaryRedisStore store, ObjectMapper json, Duration ttl) {
        this.store = Objects.requireNonNull(store, "store");
        this.json = Objects.requireNonNull(json, "json");
        this.ttl = Objects.requireNonNull(ttl, "ttl");
        if (ttl.isZero() || ttl.isNegative()) throw new IllegalArgumentException("Cache TTL must be positive");
    }

    @Override
    public Optional<Map<String, List<DictionaryModels.DisplayValue>>> find(
        long revision, List<String> types) {
        try {
            List<String> keys = types.stream().map(type -> key(revision, type)).toList();
            List<String> encoded = store.multiGet(keys);
            if (encoded == null || encoded.size() != types.size()) return Optional.empty();
            Map<String, List<DictionaryModels.DisplayValue>> result = new LinkedHashMap<>();
            for (int index = 0; index < types.size(); index++) {
                String value = encoded.get(index);
                if (value == null) continue;
                CacheEntry entry = json.readValue(value, CacheEntry.class);
                if (!types.get(index).equals(entry.dictType()) || !valid(entry.values())) {
                    return Optional.empty();
                }
                result.put(entry.dictType(), List.copyOf(entry.values()));
            }
            return Optional.of(Map.copyOf(result));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    @Override
    public void store(long revision, Map<String, List<DictionaryModels.DisplayValue>> values) {
        Map<String, String> encoded = new LinkedHashMap<>();
        values.forEach((dictType, items) -> {
            try {
                if (!valid(items)) throw new IllegalArgumentException("Dictionary values are invalid");
                encoded.put(key(revision, dictType),
                    json.writeValueAsString(new CacheEntry(dictType, List.copyOf(items))));
            } catch (JacksonException exception) {
                throw new IllegalStateException("Dictionary cache value could not be encoded", exception);
            }
        });
        if (!encoded.isEmpty()) store.putAll(encoded, ttl);
    }

    private static String key(long revision, String dictType) {
        if (revision < 0) throw new IllegalArgumentException("Catalog revision cannot be negative");
        return PREFIX + revision + ':' + dictType;
    }

    private static boolean valid(List<DictionaryModels.DisplayValue> values) {
        if (values == null || values.size() > MAX_VALUES_PER_TYPE) return false;
        Set<String> observedValues = new HashSet<>();
        for (DictionaryModels.DisplayValue item : values) {
            if (item == null || item.value() == null || item.value().isBlank()
                || !item.value().equals(item.value().trim()) || item.value().length() > 100
                || item.label() == null || item.label().isBlank()
                || !item.label().equals(item.label().trim()) || item.label().length() > 100
                || !COLORS.contains(item.color()) || !observedValues.add(item.value())) {
                return false;
            }
        }
        return true;
    }

    private record CacheEntry(String dictType, List<DictionaryModels.DisplayValue> values) { }
}
