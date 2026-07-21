package com.niv.payment.permission.cache;

import com.niv.payment.permission.domain.GrantSnapshot;
import com.niv.payment.permission.port.PermissionGrantCache;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

public final class RedisPermissionGrantCache implements PermissionGrantCache {
    private final RedisValueStore redis;
    private final GrantSnapshotCodec codec;
    private final Duration ttl;

    public RedisPermissionGrantCache(RedisValueStore redis, GrantSnapshotCodec codec, Duration ttl) {
        this.redis = Objects.requireNonNull(redis, "redis");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.ttl = Objects.requireNonNull(ttl, "ttl");
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("Permission cache TTL must be positive");
        }
    }

    @Override
    public Optional<GrantSnapshot> find(long tenantId, long membershipId, long permissionVersion) {
        PermissionCacheKey key = new PermissionCacheKey(tenantId, membershipId, permissionVersion);
        return redis.get(key.redisKey())
            .map(codec::decode)
            .filter(snapshot -> snapshot.refreshAfter() == null)
            .filter(snapshot -> snapshot.tenantId() == key.tenantId()
                && snapshot.membershipId() == key.membershipId()
                && snapshot.permissionVersion() == key.permissionVersion());
    }

    @Override
    public void store(GrantSnapshot snapshot) {
        if (snapshot.refreshAfter() != null) {
            return;
        }
        PermissionCacheKey key = new PermissionCacheKey(
            snapshot.tenantId(), snapshot.membershipId(), snapshot.permissionVersion());
        redis.set(key.redisKey(), codec.encode(snapshot), ttl);
    }
}
