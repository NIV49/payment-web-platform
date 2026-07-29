package com.niv.payment.permission.cache;

import org.springframework.data.redis.core.RedisTemplate;

import java.time.Duration;
import java.util.Optional;

public final class SpringRedisValueStore implements RedisValueStore {
    private final RedisTemplate<String, byte[]> redis;

    public SpringRedisValueStore(RedisTemplate<String, byte[]> redis) {
        this.redis = redis;
    }

    @Override
    public Optional<byte[]> get(String key) {
        return Optional.ofNullable(redis.opsForValue().get(key));
    }

    @Override
    public void set(String key, byte[] value, Duration ttl) {
        redis.opsForValue().set(key, value, ttl);
    }
}
