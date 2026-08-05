package com.niv.payment.identity.oidc;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class RedisOidcSessionIndex implements OidcSessionIndex {
    private static final String PROCESSING = "PROCESSING";
    private static final String COMPLETED = "COMPLETED";
    private static final DefaultRedisScript<Long> REGISTER = new DefaultRedisScript<>("""
        redis.call('SADD', KEYS[1], ARGV[1])
        redis.call('PEXPIRE', KEYS[1], ARGV[2])
        redis.call('SADD', KEYS[2], ARGV[1])
        redis.call('PEXPIRE', KEYS[2], ARGV[2])
        return 1
        """, Long.class);
    private static final DefaultRedisScript<Long> COMPLETE_EVENT = new DefaultRedisScript<>("""
        if redis.call('GET', KEYS[1]) == ARGV[1] then
            redis.call('SET', KEYS[1], ARGV[2], 'PX', ARGV[3])
            return 1
        end
        return 0
        """, Long.class);
    private static final DefaultRedisScript<Long> RELEASE_EVENT = new DefaultRedisScript<>("""
        if redis.call('GET', KEYS[1]) == ARGV[1] then
            return redis.call('DEL', KEYS[1])
        end
        return 0
        """, Long.class);

    private final StringRedisTemplate redis;
    private final String namespace;
    private final Duration sessionTtl;
    private final Duration processingTtl;
    private final Duration completedTtl;

    public RedisOidcSessionIndex(StringRedisTemplate redis, String namespace, Duration sessionTtl,
                                 Duration processingTtl, Duration completedTtl) {
        this.redis = Objects.requireNonNull(redis, "redis");
        this.namespace = requireNamespace(namespace);
        this.sessionTtl = requirePositive(sessionTtl, "sessionTtl");
        this.processingTtl = requirePositive(processingTtl, "processingTtl");
        this.completedTtl = requirePositive(completedTtl, "completedTtl");
    }

    @Override
    public void register(String issuer, String subject, String sessionId, long membershipId) {
        if (membershipId <= 0) {
            throw new IllegalArgumentException("Membership id must be positive");
        }
        Long registered = redis.execute(REGISTER,
            List.of(identityKey("sid", issuer, sessionId), identityKey("sub", issuer, subject)),
            Long.toString(membershipId), Long.toString(sessionTtl.toMillis()));
        if (!Long.valueOf(1L).equals(registered)) {
            throw new IllegalStateException("OIDC session index registration failed");
        }
    }

    @Override
    public Set<Long> findBySession(String issuer, String sessionId) {
        return memberships(identityKey("sid", issuer, sessionId));
    }

    @Override
    public Set<Long> findBySubject(String issuer, String subject) {
        return memberships(identityKey("sub", issuer, subject));
    }

    @Override
    public EventClaim claimEvent(String issuer, String eventId) {
        String key = identityKey("event", issuer, eventId);
        String current = redis.opsForValue().get(key);
        if (COMPLETED.equals(current)) {
            return EventClaim.completed();
        }
        String owner = UUID.randomUUID().toString();
        Boolean acquired = redis.opsForValue().setIfAbsent(key, processingValue(owner), processingTtl);
        if (Boolean.TRUE.equals(acquired)) {
            return EventClaim.acquired(owner);
        }
        return COMPLETED.equals(redis.opsForValue().get(key))
            ? EventClaim.completed() : EventClaim.inProgress();
    }

    @Override
    public void completeEvent(String issuer, String eventId, String owner) {
        String key = identityKey("event", issuer, eventId);
        Long completed = redis.execute(COMPLETE_EVENT, List.of(key), processingValue(owner),
            COMPLETED, Long.toString(completedTtl.toMillis()));
        if (!Long.valueOf(1L).equals(completed) && !COMPLETED.equals(redis.opsForValue().get(key))) {
            throw new IllegalStateException("OIDC logout event processing lease was lost");
        }
    }

    @Override
    public void releaseEvent(String issuer, String eventId, String owner) {
        String key = identityKey("event", issuer, eventId);
        redis.execute(RELEASE_EVENT, List.of(key), processingValue(owner));
    }

    private Set<Long> memberships(String key) {
        Set<String> encoded = redis.opsForSet().members(key);
        if (encoded == null || encoded.isEmpty()) {
            return Set.of();
        }
        Set<Long> result = new LinkedHashSet<>();
        for (String value : encoded) {
            try {
                long membershipId = Long.parseLong(value);
                if (membershipId > 0) {
                    result.add(membershipId);
                }
            } catch (NumberFormatException exception) {
                throw new IllegalStateException(
                    "OIDC session index contains an invalid membership id", exception);
            }
        }
        return Set.copyOf(result);
    }

    private String identityKey(String kind, String issuer, String identifier) {
        String source = required(issuer) + '\0' + required(identifier);
        return "iam:{" + namespace + "}:oidc-session:" + kind + ":" + sha256(source);
    }

    private static String processingValue(String owner) {
        if (owner == null || !owner.matches("[0-9a-f-]{36}")) {
            throw new IllegalArgumentException("OIDC logout event owner is invalid");
        }
        return PROCESSING + ":" + owner;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String required(String value) {
        if (value == null || value.isBlank() || value.length() > 1024) {
            throw new IllegalArgumentException("OIDC session identity is invalid");
        }
        return value;
    }

    private static String requireNamespace(String value) {
        if (value == null || !value.matches("[a-z][a-z0-9-]{0,31}")) {
            throw new IllegalArgumentException("OIDC Redis namespace is invalid");
        }
        return value;
    }

    private static Duration requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
