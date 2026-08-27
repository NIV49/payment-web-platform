package com.niv.payment.dictionary.cache;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.connection.RedisStringCommands.SetOption;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.types.Expiration;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class SpringDictionaryRedisStore implements DictionaryRedisStore {
    private final StringRedisTemplate redis;

    public SpringDictionaryRedisStore(StringRedisTemplate redis) {
        this.redis = Objects.requireNonNull(redis, "redis");
    }

    @Override
    public List<String> multiGet(List<String> keys) {
        return redis.opsForValue().multiGet(keys);
    }

    @Override
    public void putAll(Map<String, String> values, Duration ttl) {
        redis.executePipelined((RedisCallback<Object>) connection -> {
            values.forEach((key, value) -> connection.stringCommands().set(
                redis.getStringSerializer().serialize(key),
                redis.getStringSerializer().serialize(value),
                Expiration.from(ttl), SetOption.UPSERT));
            return null;
        });
    }
}
