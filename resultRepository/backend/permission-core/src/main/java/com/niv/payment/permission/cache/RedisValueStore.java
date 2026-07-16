package com.niv.payment.permission.cache;

import java.time.Duration;
import java.util.Optional;

public interface RedisValueStore {
    Optional<byte[]> get(String key);

    void set(String key, byte[] value, Duration ttl);
}
