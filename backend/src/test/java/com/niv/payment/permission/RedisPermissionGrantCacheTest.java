package com.niv.payment.permission;

import com.niv.payment.permission.cache.PermissionCacheKey;
import com.niv.payment.permission.cache.RedisPermissionGrantCache;
import com.niv.payment.permission.cache.RedisValueStore;
import com.niv.payment.permission.domain.GrantSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

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

        assertTrue(cache.get(new PermissionCacheKey(3L, 2L, 7L)).isEmpty());
    }
}
