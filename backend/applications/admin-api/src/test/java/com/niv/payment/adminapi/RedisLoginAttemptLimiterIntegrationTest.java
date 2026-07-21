package com.niv.payment.adminapi;

import com.niv.payment.permission.cache.RedisLoginAttemptLimiter;
import com.niv.payment.permission.service.AuthenticationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class RedisLoginAttemptLimiterIntegrationTest {
    private static final int CLIENT_LIMIT = 30;
    private static final int CLIENT_USERNAME_LIMIT = 5;

    @Container
    static final GenericContainer<?> VALKEY = new GenericContainer<>(
        "valkey/valkey:7.2.13-alpine@sha256:ac32d5e70f29e2be83384f5173180911b666c79a0e91ac0d074de5771638ed91")
        .withExposedPorts(6379);

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redis;
    private RedisLoginAttemptLimiter limiter;

    @BeforeEach
    void connectToValkey() {
        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(
            VALKEY.getHost(), VALKEY.getMappedPort(6379));
        connectionFactory = new LettuceConnectionFactory(configuration);
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
        Set<String> existingKeys = redis.keys("iam:login-attempt:*");
        if (!existingKeys.isEmpty()) redis.delete(existingKeys);
        limiter = new RedisLoginAttemptLimiter(
            redis, CLIENT_LIMIT, CLIENT_USERNAME_LIMIT, Duration.ofMinutes(15));
    }

    @AfterEach
    void disconnectFromValkey() {
        connectionFactory.destroy();
    }

    @Test
    void atomicallyCapsConcurrentAttemptsForOneClientAndUsername() throws Exception {
        int workers = 40;
        String client = "concurrent-client-" + UUID.randomUUID();
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();

        try (var executor = Executors.newFixedThreadPool(workers)) {
            for (int index = 0; index < workers; index++) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        limiter.acquire(client, "same-user");
                        return true;
                    } catch (AuthenticationService.RateLimitExceededException limited) {
                        return false;
                    }
                }));
            }
            ready.await();
            start.countDown();

            long allowed = 0;
            for (Future<Boolean> result : results) {
                if (result.get()) allowed++;
            }
            assertThat(allowed).isEqualTo(CLIENT_USERNAME_LIMIT);
        }
    }

    @Test
    void clientBucketStopsUsernameRotation() {
        String client = "rotation-client-" + UUID.randomUUID();
        for (int attempt = 0; attempt < CLIENT_LIMIT; attempt++) {
            limiter.acquire(client, "rotated-user-" + attempt);
        }

        assertThatThrownBy(() -> limiter.acquire(client, "one-more-username"))
            .isInstanceOf(AuthenticationService.RateLimitExceededException.class);
    }

    @Test
    void bothLuaKeysShareAClusterHashSlotWithoutExposingRawIdentity() {
        String client = "203.0.113.42";
        String username = "sensitive@example.test";

        limiter.acquire(client, username);

        Set<String> keys = redis.keys("iam:login-attempt:*");
        assertThat(keys).hasSize(2);
        String firstHashTag = hashTag(keys.iterator().next());
        assertThat(keys).allSatisfy(key -> {
            assertThat(hashTag(key)).isEqualTo(firstHashTag);
            assertThat(key).doesNotContain(client, username);
        });
    }

    private static String hashTag(String key) {
        return key.substring(key.indexOf('{') + 1, key.indexOf('}'));
    }
}
