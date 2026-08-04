package com.niv.payment.permission.cache;

import com.niv.payment.permission.domain.GrantSnapshot;
import com.niv.payment.permission.domain.AccountDomain;
import com.niv.payment.permission.port.PermissionGrantCache;

import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

public final class RedisPermissionGrantCache implements PermissionGrantCache {
    private final RedisValueStore redis;
    private final GrantSnapshotCodec codec;
    private final Duration ttl;
    private final AccountDomain accountDomain;

    public RedisPermissionGrantCache(AccountDomain accountDomain, RedisValueStore redis,
                                     GrantSnapshotCodec codec, Duration ttl) {
        this.accountDomain = Objects.requireNonNull(accountDomain, "accountDomain");
        this.redis = Objects.requireNonNull(redis, "redis");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.ttl = Objects.requireNonNull(ttl, "ttl");
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("Permission cache TTL must be positive");
        }
    }

    @Override
    public Optional<GrantSnapshot> find(long tenantId, long membershipId, long permissionVersion) {
        PermissionCacheKey key = new PermissionCacheKey(accountDomain, tenantId, membershipId, permissionVersion);
        return redis.get(key.redisKey())
            .flatMap(this::domainPayload)
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
        PermissionCacheKey key = new PermissionCacheKey(accountDomain,
            snapshot.tenantId(), snapshot.membershipId(), snapshot.permissionVersion());
        byte[] prefix = domainPrefix();
        byte[] encoded = codec.encode(snapshot);
        byte[] scoped = Arrays.copyOf(prefix, prefix.length + encoded.length);
        System.arraycopy(encoded, 0, scoped, prefix.length, encoded.length);
        redis.set(key.redisKey(), scoped, ttl);
    }

    private Optional<byte[]> domainPayload(byte[] scoped) {
        byte[] prefix = domainPrefix();
        if (scoped.length <= prefix.length) {
            return Optional.empty();
        }
        for (int index = 0; index < prefix.length; index++) {
            if (scoped[index] != prefix[index]) {
                return Optional.empty();
            }
        }
        return Optional.of(Arrays.copyOfRange(scoped, prefix.length, scoped.length));
    }

    private byte[] domainPrefix() {
        return ("iam-grant:" + accountDomain.name() + ":").getBytes(StandardCharsets.US_ASCII);
    }
}
