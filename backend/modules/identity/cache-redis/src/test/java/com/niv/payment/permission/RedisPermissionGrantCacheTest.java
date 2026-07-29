package com.niv.payment.permission;

import com.niv.payment.permission.cache.RedisPermissionGrantCache;
import com.niv.payment.permission.cache.RedisValueStore;
import com.niv.payment.permission.domain.GrantSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisPermissionGrantCacheTest {

    @Test
    void rejectsDecodedSnapshotThatDoesNotBelongToTheCacheKey() {
        RedisValueStore store = new RedisValueStore() {
            @Override
            public Optional<byte[]> get(String key) {
                return Optional.of(new byte[]{1});
            }

            @Override
            public void set(String key, byte[] value, Duration ttl) {
            }
        };
        var cache = new RedisPermissionGrantCache(store, new com.niv.payment.permission.cache.GrantSnapshotCodec() {
            @Override
            public byte[] encode(GrantSnapshot snapshot) {
                return new byte[]{1};
            }

            @Override
            public GrantSnapshot decode(byte[] value) {
                return new GrantSnapshot(999L, 3L, 7L, List.of());
            }
        }, Duration.ofMinutes(5));

        assertTrue(cache.find(3L, 2L, 7L).isEmpty());
    }

    @Test
    void rejectsLegacyTemporalSnapshotReadFromRedis() {
        RedisValueStore store = new RedisValueStore() {
            @Override
            public Optional<byte[]> get(String key) {
                return Optional.of(new byte[]{1});
            }

            @Override
            public void set(String key, byte[] value, Duration ttl) {
            }
        };
        var cache = new RedisPermissionGrantCache(store, new com.niv.payment.permission.cache.GrantSnapshotCodec() {
            @Override
            public byte[] encode(GrantSnapshot snapshot) {
                return new byte[]{1};
            }

            @Override
            public GrantSnapshot decode(byte[] value) {
                return new GrantSnapshot(2L, 3L, 7L, List.of(), Instant.parse("2100-01-01T00:00:00Z"));
            }
        }, Duration.ofMinutes(5));

        assertTrue(cache.find(3L, 2L, 7L).isEmpty());
    }

    @Test
    void doesNotWriteTemporalSnapshotToRedis() {
        AtomicInteger writes = new AtomicInteger();
        RedisValueStore store = new RedisValueStore() {
            @Override
            public Optional<byte[]> get(String key) {
                return Optional.empty();
            }

            @Override
            public void set(String key, byte[] value, Duration ttl) {
                writes.incrementAndGet();
            }
        };
        var cache = new RedisPermissionGrantCache(store, new com.niv.payment.permission.cache.GrantSnapshotCodec() {
            @Override
            public byte[] encode(GrantSnapshot snapshot) {
                return new byte[]{1};
            }

            @Override
            public GrantSnapshot decode(byte[] value) {
                throw new AssertionError("This test does not read from Redis");
            }
        }, Duration.ofMinutes(5));

        cache.store(new GrantSnapshot(2L, 3L, 7L, List.of(), Instant.parse("2100-01-01T00:00:00Z")));

        assertEquals(0, writes.get());
    }
}
