package com.niv.payment.dictionary.cache;

import com.niv.payment.dictionary.core.DictionaryModels;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RedisDictionaryBatchCacheTest {
    @Test
    void usesOneMultiGetAndRevisionScopedKeys() {
        RecordingStore store = new RecordingStore();
        var cache = new RedisDictionaryBatchCache(store, new ObjectMapper(), Duration.ofMinutes(10));
        Map<String, List<DictionaryModels.DisplayValue>> source = Map.of(
            "PAY_CHANNEL", List.of(new DictionaryModels.DisplayValue("bank", "Bank", "processing")),
            "EMPTY_TYPE", List.of());
        cache.store(17L, source);

        var result = cache.find(17L, List.of("PAY_CHANNEL", "EMPTY_TYPE", "MISS"));

        assertThat(store.multiGets).containsExactly(List.of(
            "dictionary:catalog:v17:PAY_CHANNEL",
            "dictionary:catalog:v17:EMPTY_TYPE",
            "dictionary:catalog:v17:MISS"));
        assertThat(result).isPresent();
        assertThat(result.orElseThrow()).containsOnlyKeys("PAY_CHANNEL", "EMPTY_TYPE");
        assertThat(store.ttls).containsOnly(Duration.ofMinutes(10));
        assertThat(store.batchWrites).isEqualTo(1);
    }

    @Test
    void rejectsTheWholeReadOnDecodeFailureOrCrossKeyPayload() {
        RecordingStore store = new RecordingStore();
        var cache = new RedisDictionaryBatchCache(store, new ObjectMapper(), Duration.ofMinutes(5));
        cache.store(2L, Map.of("TYPE_A", List.of()));
        store.values.put("dictionary:catalog:v2:TYPE_B",
            store.values.get("dictionary:catalog:v2:TYPE_A"));

        assertThat(cache.find(2L, List.of("TYPE_B"))).isEmpty();

        store.values.put("dictionary:catalog:v2:TYPE_A", "not-json");
        assertThat(cache.find(2L, List.of("TYPE_A"))).isEmpty();
    }

    @Test
    void rejectsTamperedDisplayValuesInsteadOfReturningRedisTruth() {
        RecordingStore store = new RecordingStore();
        var json = new ObjectMapper();
        var cache = new RedisDictionaryBatchCache(store, json, Duration.ofMinutes(5));
        cache.store(3L, Map.of("TYPE_A", List.of(
            new DictionaryModels.DisplayValue("value", "Label", "success"))));
        String valid = store.values.get("dictionary:catalog:v3:TYPE_A");

        store.values.put("dictionary:catalog:v3:TYPE_A", valid.replace("success", "blue"));
        assertThat(cache.find(3L, List.of("TYPE_A"))).isEmpty();
        store.values.put("dictionary:catalog:v3:TYPE_A", valid.replace("Label", ""));
        assertThat(cache.find(3L, List.of("TYPE_A"))).isEmpty();
    }

    private static final class RecordingStore implements DictionaryRedisStore {
        private final Map<String, String> values = new LinkedHashMap<>();
        private final List<List<String>> multiGets = new ArrayList<>();
        private final List<Duration> ttls = new ArrayList<>();
        private int batchWrites;

        @Override
        public List<String> multiGet(List<String> keys) {
            multiGets.add(List.copyOf(keys));
            List<String> result = new ArrayList<>();
            keys.forEach(key -> result.add(values.get(key)));
            return result;
        }

        @Override
        public void putAll(Map<String, String> entries, Duration ttl) {
            values.putAll(entries);
            ttls.add(ttl);
            batchWrites++;
        }
    }
}
