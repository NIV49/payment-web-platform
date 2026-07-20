package com.niv.payment.permission.cache;

import com.niv.payment.permission.service.AuthenticationService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;

public final class RedisLoginAttemptLimiter implements AuthenticationService.LoginAttemptLimiter {
    private static final DefaultRedisScript<Long> INCREMENT = new DefaultRedisScript<>("""
        local count = redis.call('INCR', KEYS[1])
        if count == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end
        return count
        """, Long.class);

    private final StringRedisTemplate redis;
    private final int maximumFailures;
    private final Duration window;

    public RedisLoginAttemptLimiter(StringRedisTemplate redis, int maximumFailures, Duration window) {
        this.redis = redis;
        this.maximumFailures = maximumFailures;
        this.window = window;
    }

    @Override
    public void requireAllowed(String bucketKey) {
        String value = redis.opsForValue().get(key(bucketKey));
        if (value != null && Integer.parseInt(value) >= maximumFailures) {
            throw new AuthenticationService.RateLimitExceededException();
        }
    }

    @Override
    public void recordFailure(String bucketKey) {
        redis.execute(INCREMENT, List.of(key(bucketKey)), Long.toString(window.toSeconds()));
    }

    @Override
    public void clear(String bucketKey) {
        redis.delete(key(bucketKey));
    }

    private static String key(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return "iam:login-attempt:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
