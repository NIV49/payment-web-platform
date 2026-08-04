package com.niv.payment.permission;

import com.niv.payment.permission.cache.RedisPermissionGrantCache;
import com.niv.payment.permission.cache.RedisValueStore;
import com.niv.payment.permission.domain.AccountDomain;
import com.niv.payment.permission.domain.GrantSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
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
                return Optional.of("iam-grant:PLATFORM:x".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            }

            @Override
            public void set(String key, byte[] value, Duration ttl) {
            }
        };
        var cache = new RedisPermissionGrantCache(AccountDomain.PLATFORM, store,
            new com.niv.payment.permission.cache.GrantSnapshotCodec() {
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
                return Optional.of("iam-grant:PLATFORM:x".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            }

            @Override
            public void set(String key, byte[] value, Duration ttl) {
            }
        };
        var cache = new RedisPermissionGrantCache(AccountDomain.PLATFORM, store,
            new com.niv.payment.permission.cache.GrantSnapshotCodec() {
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
        var cache = new RedisPermissionGrantCache(AccountDomain.PLATFORM, store,
            new com.niv.payment.permission.cache.GrantSnapshotCodec() {
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

    @Test
    void rejectsAPlatformSnapshotCopiedIntoTheMerchantNamespace() {
        Map<String, byte[]> values = new HashMap<>();
        RedisValueStore store = new RedisValueStore() {
            @Override
            public Optional<byte[]> get(String key) {
                return Optional.ofNullable(values.get(key));
            }

            @Override
            public void set(String key, byte[] value, Duration ttl) {
                values.put(key, value);
            }
        };
        var codec = new com.niv.payment.permission.cache.GrantSnapshotCodec() {
            @Override
            public byte[] encode(GrantSnapshot snapshot) {
                return new byte[]{1};
            }

            @Override
            public GrantSnapshot decode(byte[] value) {
                return new GrantSnapshot(2L, 3L, 7L, List.of());
            }
        };
        var platform = new RedisPermissionGrantCache(
            AccountDomain.PLATFORM, store, codec, Duration.ofMinutes(5));
        var merchant = new RedisPermissionGrantCache(
            AccountDomain.MERCHANT, store, codec, Duration.ofMinutes(5));
        platform.store(new GrantSnapshot(2L, 3L, 7L, List.of()));
        values.put("iam:merchant:grant:3:2:v7", values.get("iam:platform:grant:3:2:v7"));

        assertTrue(merchant.find(3L, 2L, 7L).isEmpty());
    }

    @Test
    void usesDistinctKeysForTheSameIdentityInEveryAccountDomain() {
        Map<String, byte[]> values = new HashMap<>();
        RedisValueStore store = new RedisValueStore() {
            @Override
            public Optional<byte[]> get(String key) {
                return Optional.ofNullable(values.get(key));
            }

            @Override
            public void set(String key, byte[] value, Duration ttl) {
                values.put(key, value);
            }
        };
        var codec = new com.niv.payment.permission.cache.GrantSnapshotCodec() {
            public byte[] encode(GrantSnapshot snapshot) {
                return new byte[]{1};
            }

            public GrantSnapshot decode(byte[] value) {
                return new GrantSnapshot(2L, 3L, 7L, List.of());
            }
        };
        GrantSnapshot snapshot = new GrantSnapshot(2L, 3L, 7L, List.of());

        new RedisPermissionGrantCache(AccountDomain.PLATFORM, store, codec, Duration.ofMinutes(5))
            .store(snapshot);
        new RedisPermissionGrantCache(AccountDomain.MERCHANT, store, codec, Duration.ofMinutes(5))
            .store(snapshot);
        new RedisPermissionGrantCache(AccountDomain.AGENT, store, codec, Duration.ofMinutes(5))
            .store(snapshot);

        assertEquals(
            java.util.Set.of(
                "iam:platform:grant:3:2:v7",
                "iam:merchant:grant:3:2:v7",
                "iam:agent:grant:3:2:v7"),
            values.keySet());
    }
}
