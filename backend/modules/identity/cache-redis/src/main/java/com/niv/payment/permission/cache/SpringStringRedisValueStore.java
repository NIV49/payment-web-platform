package com.niv.payment.permission.cache;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;

/** Stores binary cache values through Boot's standard StringRedisTemplate. */
public final class SpringStringRedisValueStore implements RedisValueStore {
    private final StringRedisTemplate redis;

    public SpringStringRedisValueStore(StringRedisTemplate redis) {
        this.redis = Objects.requireNonNull(redis, "redis");
    }

    @Override
    public Optional<byte[]> get(String key) {
        return Optional.ofNullable(redis.opsForValue().get(key)).map(Base64.getDecoder()::decode);
    }

    @Override
    public void set(String key, byte[] value, Duration ttl) {
        redis.opsForValue().set(key, Base64.getEncoder().encodeToString(value), ttl);
    }
}
