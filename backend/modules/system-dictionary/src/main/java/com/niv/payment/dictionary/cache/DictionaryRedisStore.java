package com.niv.payment.dictionary.cache;

import java.time.Duration;
import java.util.List;
import java.util.Map;

public interface DictionaryRedisStore {
    List<String> multiGet(List<String> keys);
    void putAll(Map<String, String> values, Duration ttl);
}
