package com.niv.payment.identity.oidc;

import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

public final class RedisOidcStateStore implements OidcFlowService.LoginTransactionStore,
    OidcFlowService.HandoffStore, OidcStepUpFlowService.TransactionStore,
    OidcStepUpFlowService.HandoffStore {
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final String namespace;
    private final Duration transactionTtl;
    private final Duration handoffTtl;

    public RedisOidcStateStore(StringRedisTemplate redis, ObjectMapper json, String namespace,
                               Duration transactionTtl, Duration handoffTtl) {
        this.redis = Objects.requireNonNull(redis, "redis");
        this.json = Objects.requireNonNull(json, "json");
        this.namespace = requireNamespace(namespace);
        this.transactionTtl = requirePositive(transactionTtl, "transactionTtl");
        this.handoffTtl = requirePositive(handoffTtl, "handoffTtl");
    }

    @Override
    public void putTransaction(String state, OidcFlowService.LoginTransaction transaction) {
        put(key("transaction", state), transaction, transactionTtl);
    }

    @Override
    public Optional<OidcFlowService.LoginTransaction> takeTransaction(String state) {
        return take(key("transaction", state), OidcFlowService.LoginTransaction.class);
    }

    @Override
    public void putHandoff(String code, OidcFlowService.LoginHandoff handoff) {
        put(key("handoff", code), handoff, handoffTtl);
    }

    @Override
    public Optional<OidcFlowService.LoginHandoff> takeHandoff(String code) {
        return take(key("handoff", code), OidcFlowService.LoginHandoff.class);
    }

    @Override
    public void putStepUpTransaction(String state, OidcStepUpFlowService.StepUpTransaction transaction) {
        put(key("step-up-transaction", state), transaction, transactionTtl);
    }

    @Override
    public Optional<OidcStepUpFlowService.StepUpTransaction> takeStepUpTransaction(String state) {
        return take(key("step-up-transaction", state), OidcStepUpFlowService.StepUpTransaction.class);
    }

    @Override
    public void putStepUpHandoff(String code, OidcStepUpFlowService.StepUpHandoff handoff) {
        put(key("step-up-handoff", code), handoff, handoffTtl);
    }

    @Override
    public Optional<OidcStepUpFlowService.StepUpHandoff> takeStepUpHandoff(String code) {
        return take(key("step-up-handoff", code), OidcStepUpFlowService.StepUpHandoff.class);
    }

    private void put(String key, Object value, Duration ttl) {
        try {
            redis.opsForValue().set(key, json.writeValueAsString(value), ttl);
        } catch (JacksonException exception) {
            throw new IllegalStateException("OIDC state could not be encoded", exception);
        }
    }

    private <T> Optional<T> take(String key, Class<T> type) {
        String encoded = redis.opsForValue().getAndDelete(key);
        if (encoded == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(json.readValue(encoded, type));
        } catch (JacksonException exception) {
            throw new IllegalStateException("OIDC state could not be decoded", exception);
        }
    }

    private String key(String kind, String opaque) {
        return "iam:" + namespace + ":oidc:" + kind + ":" + sha256(opaque);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
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
