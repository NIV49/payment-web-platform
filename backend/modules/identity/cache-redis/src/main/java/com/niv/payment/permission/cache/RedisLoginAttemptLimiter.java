package com.niv.payment.permission.cache;

import com.niv.payment.permission.domain.AccountDomain;
import com.niv.payment.permission.service.AuthenticationService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

public final class RedisLoginAttemptLimiter implements AuthenticationService.LoginAttemptLimiter {
    private static final DefaultRedisScript<Long> ACQUIRE = new DefaultRedisScript<>("""
        local clientCount = tonumber(redis.call('GET', KEYS[1]) or '0')
        local accountCount = tonumber(redis.call('GET', KEYS[2]) or '0')
        local clientLimit = tonumber(ARGV[1])
        local accountLimit = tonumber(ARGV[2])
        if clientCount >= clientLimit or accountCount >= accountLimit then
            return 0
        end
        clientCount = redis.call('INCR', KEYS[1])
        if clientCount == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[3]) end
        accountCount = redis.call('INCR', KEYS[2])
        if accountCount == 1 then redis.call('PEXPIRE', KEYS[2], ARGV[3]) end
        return 1
        """, Long.class);
    private static final DefaultRedisScript<Long> RECORD_SUCCESS = new DefaultRedisScript<>("""
        redis.call('DEL', KEYS[2])
        local clientCount = tonumber(redis.call('GET', KEYS[1]) or '0')
        if clientCount <= 1 then
            redis.call('DEL', KEYS[1])
        else
            redis.call('DECR', KEYS[1])
        end
        return 1
        """, Long.class);

    private final StringRedisTemplate redis;
    private final int maximumClientFailures;
    private final int maximumClientUsernameFailures;
    private final Duration window;
    private final String namespace;

    public RedisLoginAttemptLimiter(AccountDomain accountDomain, StringRedisTemplate redis,
                                    int maximumClientFailures,
                                    int maximumClientUsernameFailures, Duration window) {
        this.namespace = Objects.requireNonNull(accountDomain, "accountDomain").cacheNamespace();
        this.redis = Objects.requireNonNull(redis, "redis");
        if (maximumClientFailures < 1 || maximumClientUsernameFailures < 1) {
            throw new IllegalArgumentException("Login attempt limits must be positive");
        }
        this.window = Objects.requireNonNull(window, "window");
        if (window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("Login attempt window must be positive");
        }
        this.maximumClientFailures = maximumClientFailures;
        this.maximumClientUsernameFailures = maximumClientUsernameFailures;
    }

    @Override
    public void acquire(String clientKey, String normalizedUsername) {
        List<String> keys = keys(clientKey, normalizedUsername);
        Long allowed = redis.execute(ACQUIRE, keys,
            Integer.toString(maximumClientFailures),
            Integer.toString(maximumClientUsernameFailures),
            Long.toString(window.toMillis()));
        if (!Long.valueOf(1).equals(allowed)) {
            throw new AuthenticationService.RateLimitExceededException();
        }
    }

    @Override
    public void recordSuccess(String clientKey, String normalizedUsername) {
        redis.execute(RECORD_SUCCESS, keys(clientKey, normalizedUsername));
    }

    private List<String> keys(String clientKey, String normalizedUsername) {
        Objects.requireNonNull(clientKey, "clientKey");
        Objects.requireNonNull(normalizedUsername, "normalizedUsername");
        String clientDigest = digest(clientKey);
        String clusterSlot = "{" + clientDigest + "}";
        return List.of(
            "iam:" + namespace + ":login-attempt:" + clusterSlot + ":client",
            "iam:" + namespace + ":login-attempt:" + clusterSlot + ":username:" + digest(normalizedUsername));
    }

    private static String digest(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
