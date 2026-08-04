package com.niv.payment.iam001;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ThreeBackofficeBoundaryIntegrationTest {
    private static final String POSTGRES_IMAGE =
        "postgres:18.4-alpine@sha256:9a8afca54e7861fd90fab5fdf4c42477a6b1cb7d293595148e674e0a3181de15";
    private static final String VALKEY_IMAGE =
        "valkey/valkey:7.2.13-alpine@sha256:ac32d5e70f29e2be83384f5173180911b666c79a0e91ac0d074de5771638ed91";
    private static final String VALKEY_AUTH_VALUE = "iam001-valkey";
    private static final String LOGIN_TEST_VALUE = "Iam001-Initial-Password-2026";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
        DockerImageName.parse(POSTGRES_IMAGE).asCompatibleSubstituteFor("postgres"))
        .withDatabaseName("payment_platform")
        .withUsername("payment_dev")
        .withPassword("payment_dev");

    @Container
    static final GenericContainer<?> VALKEY = new GenericContainer<>(VALKEY_IMAGE)
        .withCommand("valkey-server", "--requirepass", VALKEY_AUTH_VALUE)
        .withExposedPorts(6379);

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        .build();
    private static final List<Process> PROCESSES = new ArrayList<>();
    private static List<Root> roots;

    @BeforeAll
    static void startThreeIndependentBackoffices() throws Exception {
        Root platform = new Root("platform", freePort(), "http://127.0.0.1:5999",
            "admin", "PAYMENT_PLATFORM_SESSION", 1000L, "platform-admin");
        Root merchant = new Root("merchant", freePort(), "http://127.0.0.1:6002",
            "merchant-admin", "PAYMENT_MERCHANT_SESSION", 2100L, "merchant-admin");
        Root agent = new Root("agent", freePort(), "http://127.0.0.1:6001",
            "agent-admin", "PAYMENT_AGENT_SESSION", 3100L, "agent-admin");
        roots = List.of(platform, merchant, agent);

        start(platform, "applications/admin-api/target/admin-api-0.1.0-SNAPSHOT.jar", Map.of(
            "SPRING_PROFILES_ACTIVE", "local",
            "PAYMENT_ADMIN_BIND_ADDRESS", "127.0.0.1",
            "PAYMENT_ADMIN_PORT", Integer.toString(platform.port()),
            "PAYMENT_PLATFORM_ALLOWED_ORIGIN", platform.origin(),
            "PAYMENT_FLYWAY_ENABLED", "true",
            "PAYMENT_BOOTSTRAP_PASSWORD", LOGIN_TEST_VALUE));
        waitUntilReady(platform);

        start(merchant,
            "applications/merchant-admin-api/target/merchant-admin-api-0.1.0-SNAPSHOT.jar",
            Map.of("PAYMENT_MERCHANT_PORT", Integer.toString(merchant.port()),
                "PAYMENT_MERCHANT_ALLOWED_ORIGIN", merchant.origin()));
        start(agent,
            "applications/agent-admin-api/target/agent-admin-api-0.1.0-SNAPSHOT.jar",
            Map.of("PAYMENT_AGENT_PORT", Integer.toString(agent.port()),
                "PAYMENT_AGENT_ALLOWED_ORIGIN", agent.origin()));
        waitUntilReady(merchant);
        waitUntilReady(agent);
    }

    @AfterAll
    static void stopThreeIndependentBackoffices() {
        for (Process process : PROCESSES.reversed()) {
            process.destroy();
            try {
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
    }

    @Test
    @Order(1)
    void wrongAccountDomainLoginIsRejectedAndMatchingDomainSucceeds() throws Exception {
        for (Root root : roots) {
            for (Root account : roots) {
                Response response = login(root, account.username(), LOGIN_TEST_VALUE, null);
                if (root == account) {
                    assertThat(response.status()).as(root.name() + " own-domain login").isEqualTo(200);
                    assertThat(response.cookie(root.cookieName())).isNotBlank();
                } else {
                    assertThat(response.status()).as(root.name() + " rejects " + account.name())
                        .isEqualTo(401);
                    assertThat(response.cookies()).isEmpty();
                }
            }
        }
    }

    @Test
    @Order(2)
    void untrustedOriginsCannotCreateOrTerminateSessions() throws Exception {
        for (Root root : roots) {
            List<String> rejectedOrigins = new ArrayList<>();
            rejectedOrigins.add("https://attacker.invalid");
            roots.stream()
                .filter(candidate -> candidate != root)
                .map(Root::origin)
                .forEach(rejectedOrigins::add);
            rejectedOrigins.add("");
            for (String origin : rejectedOrigins) {
                String requestOrigin = origin.isEmpty() ? null : origin;
                Response rejectedLogin = send(root, "POST", "/api/auth/login", null, requestOrigin,
                    "{\"username\":\"" + root.username() + "\",\"password\":\"" + LOGIN_TEST_VALUE + "\"}");
                assertThat(rejectedLogin.status()).as(root.name() + " login origin " + origin).isEqualTo(403);
                assertThat(rejectedLogin.cookies()).isEmpty();

                String cookie = login(root, root.username(), LOGIN_TEST_VALUE, null).cookie(root.cookieName());
                Response rejectedLogout = send(root, "POST", "/api/auth/logout", cookie, requestOrigin, null);
                assertThat(rejectedLogout.status()).as(root.name() + " logout origin " + origin).isEqualTo(403);
                assertThat(rejectedLogout.cookies()).isEmpty();
                assertThat(get(root, "/api/user/info", cookie).status())
                    .as(root.name() + " rejected logout must not mutate the session").isEqualTo(200);

                Response acceptedLogout = send(root, "POST", "/api/auth/logout", cookie, root.origin(), null);
                assertThat(acceptedLogout.status()).isEqualTo(200);
                assertThat(acceptedLogout.firstHeader("set-cookie"))
                    .contains(root.cookieName(), "Max-Age=0");
                assertThat(get(root, "/api/user/info", cookie).status()).isEqualTo(401);
            }
        }
    }

    @Test
    @Order(3)
    void activeMembershipWithoutPortalGrantCannotLogin() throws Exception {
        for (Root root : roots) {
            String permissionCode = "backoffice:" + root.name() + "-access";
            try {
                update("""
                    UPDATE iam_role_grant SET status='DISABLED', row_version=row_version+1
                     WHERE tenant_id=(SELECT tenant_id FROM iam_membership WHERE id=?)
                       AND permission_id=(SELECT id FROM iam_permission WHERE permission_code=?)
                    """, root.membershipId(), permissionCode);
                assertThat(queryString("SELECT status FROM iam_membership WHERE id=?", root.membershipId()))
                    .isEqualTo("ACTIVE");
                assertThat(login(root, root.username(), LOGIN_TEST_VALUE, null).status())
                    .as(root.name() + " requires explicit portal access").isEqualTo(401);
            } finally {
                update("""
                    UPDATE iam_role_grant SET status='ACTIVE', row_version=row_version+1
                     WHERE tenant_id=(SELECT tenant_id FROM iam_membership WHERE id=?)
                       AND permission_id=(SELECT id FROM iam_permission WHERE permission_code=?)
                    """, root.membershipId(), permissionCode);
            }
        }
    }

    @Test
    @Order(4)
    void tenantIdCannotSelectAWorkspace() throws Exception {
        for (Root root : roots) {
            Response response = login(root, root.username(), LOGIN_TEST_VALUE, "\"tenantId\":\"1\",");
            assertThat(response.status()).as(root.name()).isEqualTo(400);
            assertThat(response.body()).contains("INVALID_REQUEST");
            assertThat(response.cookies()).isEmpty();
        }
    }

    @Test
    @Order(5)
    void crossRealmCookieTokenAndCacheNamespacesCannotCrossRoots() throws Exception {
        Map<Root, String> sessions = new LinkedHashMap<>();
        for (Root root : roots) {
            sessions.put(root, login(root, root.username(), LOGIN_TEST_VALUE, null).cookie(root.cookieName()));
        }

        for (Root source : roots) {
            for (Root target : roots) {
                if (source == target) {
                    continue;
                }
                String sessionValue = sessions.get(source).substring(source.cookieName().length() + 1);
                assertThat(get(target, "/api/user/info", sessions.get(source)).status())
                    .as(source.name() + " Cookie name at " + target.name()).isEqualTo(401);
                assertThat(get(target, "/api/user/info", target.cookieName() + "=" + sessionValue).status())
                    .as(source.name() + " token renamed for " + target.name()).isEqualTo(401);
                assertThat(get(source, "/api/user/info", sessions.get(source)).status())
                    .as(source.name() + " source session remains valid").isEqualTo(200);
            }
        }

        String keys = VALKEY.execInContainer("valkey-cli", "-a", VALKEY_AUTH_VALUE,
            "--no-auth-warning", "--scan").getStdout();
        for (Root root : roots) {
            assertThat(keys).contains("iam:" + root.name() + ":login-attempt:");
            assertThat(keys).contains(root.cookieName() + ":" + root.loginType());
        }
    }

    @Test
    @Order(6)
    void inactiveMembershipInvalidatesExistingSessions() throws Exception {
        for (Root root : roots) {
            String cookie = login(root, root.username(), LOGIN_TEST_VALUE, null).cookie(root.cookieName());
            try {
                update("UPDATE iam_membership SET status='DISABLED' WHERE id=?", root.membershipId());
                Response rejected = get(root, "/api/user/info", cookie);
                assertThat(rejected.status()).as(root.name()).isEqualTo(401);
                assertThat(rejected.firstHeader("set-cookie")).contains(root.cookieName(), "Max-Age=0");
            } finally {
                update("""
                    UPDATE iam_membership
                       SET status='ACTIVE', session_version=session_version+1
                     WHERE id=?
                    """, root.membershipId());
            }
        }
    }

    @Test
    @Order(7)
    void passwordChangeInvalidatesTheOldSessionAndCredential() throws Exception {
        Root merchant = root("merchant");
        String oldCookie = login(merchant, merchant.username(), LOGIN_TEST_VALUE, null)
            .cookie(merchant.cookieName());
        String originalHash = queryString(
            "SELECT password_hash FROM iam_authentication_credential WHERE username=?", merchant.username());
        String replacement = "Iam001-Replaced-Password-2026";
        String replacementHash = new BCryptPasswordEncoder(10).encode(replacement);

        try {
            inTransaction(connection -> {
                execute(connection, """
                    UPDATE iam_authentication_credential
                       SET password_hash=?, row_version=row_version+1
                     WHERE username=?
                    """, replacementHash, merchant.username());
                execute(connection, """
                    UPDATE iam_membership SET session_version=session_version+1 WHERE id=?
                    """, merchant.membershipId());
            });

            assertThat(get(merchant, "/api/user/info", oldCookie).status()).isEqualTo(401);
            assertThat(login(merchant, merchant.username(), LOGIN_TEST_VALUE, null).status()).isEqualTo(401);
            assertThat(login(merchant, merchant.username(), replacement, null).status()).isEqualTo(200);
        } finally {
            inTransaction(connection -> {
                execute(connection, """
                    UPDATE iam_authentication_credential
                       SET password_hash=?, row_version=row_version+1
                     WHERE username=?
                    """, originalHash, merchant.username());
                execute(connection, """
                    UPDATE iam_membership SET session_version=session_version+1 WHERE id=?
                    """, merchant.membershipId());
            });
        }
    }

    @Test
    @Order(8)
    void committedGrantRevocationInvalidatesOldSessionAndRefreshesCodes() throws Exception {
        Root platform = root("platform");
        String oldCookie = login(platform, platform.username(), LOGIN_TEST_VALUE, null)
            .cookie(platform.cookieName());
        assertThat(get(platform, "/api/auth/codes", oldCookie).body()).contains("user:view");

        try {
            inTransaction(connection -> {
                execute(connection, """
                    UPDATE iam_role_grant SET status='DISABLED', row_version=row_version+1
                     WHERE tenant_id=1 AND role_id=2000
                       AND permission_id=(SELECT id FROM iam_permission WHERE permission_code='user:view')
                    """);
                execute(connection, """
                    UPDATE iam_membership SET permission_version=permission_version+1 WHERE id=1000
                    """);
            });

            assertThat(get(platform, "/api/auth/codes", oldCookie).status()).isEqualTo(401);
            String newCookie = login(platform, platform.username(), LOGIN_TEST_VALUE, null)
                .cookie(platform.cookieName());
            assertThat(get(platform, "/api/auth/codes", newCookie).body()).doesNotContain("user:view");
        } finally {
            inTransaction(connection -> {
                execute(connection, """
                    UPDATE iam_role_grant SET status='ACTIVE', row_version=row_version+1
                     WHERE tenant_id=1 AND role_id=2000
                       AND permission_id=(SELECT id FROM iam_permission WHERE permission_code='user:view')
                    """);
                execute(connection, """
                    UPDATE iam_membership SET permission_version=permission_version+1 WHERE id=1000
                    """);
            });
        }
    }

    @Test
    @Order(9)
    void unknownRouteAndFrontendHiddenDirectRoutesAreDenied() throws Exception {
        for (Root root : roots) {
            String cookie = login(root, root.username(), LOGIN_TEST_VALUE, null).cookie(root.cookieName());
            assertThat(get(root, "/api/not-registered", cookie).status()).as(root.name()).isEqualTo(403);
            if (!root.name().equals("platform")) {
                assertThat(get(root, "/api/system/user/list?page=1&pageSize=20", cookie).status())
                    .as(root.name() + " direct platform API").isEqualTo(403);
            }
        }

        Root platform = root("platform");
        try {
            inTransaction(connection -> {
                execute(connection, """
                    UPDATE iam_role_grant SET status='DISABLED', row_version=row_version+1
                     WHERE tenant_id=1 AND role_id=2000
                       AND permission_id=(SELECT id FROM iam_permission WHERE permission_code='user:view')
                    """);
                execute(connection, """
                    UPDATE iam_membership SET permission_version=permission_version+1 WHERE id=1000
                    """);
            });
            String restricted = login(platform, platform.username(), LOGIN_TEST_VALUE, null)
                .cookie(platform.cookieName());
            assertThat(restricted).isNotBlank();
            assertThat(get(platform, "/api/system/user/list?page=1&pageSize=20", restricted).status())
                .as("platform registered API without user:view").isEqualTo(403);
        } finally {
            inTransaction(connection -> {
                execute(connection, """
                    UPDATE iam_role_grant SET status='ACTIVE', row_version=row_version+1
                     WHERE tenant_id=1 AND role_id=2000
                       AND permission_id=(SELECT id FROM iam_permission WHERE permission_code='user:view')
                    """);
                execute(connection, """
                    UPDATE iam_membership SET permission_version=permission_version+1 WHERE id=1000
                    """);
            });
        }
    }

    @Test
    @Order(10)
    void concurrentRevocationWinsAfterTheCommitBoundary() throws Exception {
        Root platform = root("platform");
        String cookie = login(platform, platform.username(), LOGIN_TEST_VALUE, null)
            .cookie(platform.cookieName());
        String protectedUserList = "/api/system/user/list?page=1&pageSize=20";
        Response warmed = get(platform, protectedUserList, cookie);
        assertThat(warmed.status()).isEqualTo(200);
        long versionBefore = Long.parseLong(queryString(
            "SELECT permission_version FROM iam_membership WHERE id=?", platform.membershipId()));
        assertThat(redisGrantKeys()).contains(
            "iam:platform:grant:1:" + platform.membershipId() + ":v" + versionBefore);

        CountDownLatch ready = new CountDownLatch(8);
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean committed = new AtomicBoolean(false);
        List<Observation> observations = new CopyOnWriteArrayList<>();
        List<CompletableFuture<Void>> requests = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(8)) {
            for (int worker = 0; worker < 8; worker++) {
                requests.add(CompletableFuture.runAsync(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        for (int attempt = 0; attempt < 8; attempt++) {
                            boolean afterCommit = committed.get();
                            observations.add(new Observation(
                                afterCommit, get(platform, protectedUserList, cookie).status()));
                        }
                    } catch (Exception exception) {
                        throw new IllegalStateException(exception);
                    }
                }, executor));
            }
            try {
                assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
                start.countDown();
                inTransaction(connection -> {
                    execute(connection, """
                        UPDATE iam_role_grant SET status='DISABLED',row_version=row_version+1
                         WHERE tenant_id=1 AND role_id=2000
                           AND permission_id=(SELECT id FROM iam_permission WHERE permission_code='user:view')
                        """);
                    execute(connection, """
                        UPDATE iam_membership SET permission_version=permission_version+1 WHERE id=?
                        """, platform.membershipId());
                });
                committed.set(true);
                for (int index = 0; index < 20; index++) {
                    requests.add(CompletableFuture.runAsync(() -> {
                        try {
                            observations.add(new Observation(
                                true, get(platform, protectedUserList, cookie).status()));
                        } catch (Exception exception) {
                            throw new IllegalStateException(exception);
                        }
                    }, executor));
                }
                requests.forEach(CompletableFuture::join);

                assertThat(observations).anyMatch(observation -> !observation.afterCommit());
                assertThat(observations.stream().filter(Observation::afterCommit).map(Observation::status))
                    .containsOnly(401);

                String refreshedCookie = login(platform, platform.username(), LOGIN_TEST_VALUE, null)
                    .cookie(platform.cookieName());
                assertThat(get(platform, protectedUserList, refreshedCookie).status()).isEqualTo(403);
                Response refreshedCodes = get(platform, "/api/auth/codes", refreshedCookie);
                assertThat(refreshedCodes.status()).isEqualTo(200);
                assertThat(refreshedCodes.body()).doesNotContain("user:view");
                assertThat(redisGrantKeys()).contains(
                    "iam:platform:grant:1:" + platform.membershipId() + ":v" + (versionBefore + 1));
            } finally {
                start.countDown();
                inTransaction(connection -> {
                    execute(connection, """
                        UPDATE iam_role_grant SET status='ACTIVE',row_version=row_version+1
                         WHERE tenant_id=1 AND role_id=2000
                           AND permission_id=(SELECT id FROM iam_permission WHERE permission_code='user:view')
                        """);
                    execute(connection, """
                        UPDATE iam_membership SET permission_version=permission_version+1 WHERE id=?
                        """, platform.membershipId());
                });
            }
        }
    }

    private static String redisGrantKeys() throws IOException, InterruptedException {
        return VALKEY.execInContainer(
            "valkey-cli", "--no-auth-warning", "-a", VALKEY_AUTH_VALUE,
            "KEYS", "iam:platform:grant:*").getStdout();
    }

    private static void start(Root root, String jar, Map<String, String> rootEnvironment) throws IOException {
        Path backendRoot = Path.of("../..").toAbsolutePath().normalize();
        Path logDirectory = Path.of("target", "process-logs");
        Files.createDirectories(logDirectory);
        ProcessBuilder builder = new ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-jar", backendRoot.resolve(jar).toString());
        builder.directory(backendRoot.toFile());
        builder.redirectErrorStream(true);
        builder.redirectOutput(logDirectory.resolve(root.name() + ".log").toFile());
        Map<String, String> environment = builder.environment();
        environment.put("PAYMENT_DB_URL", POSTGRES.getJdbcUrl());
        environment.put("PAYMENT_DB_USERNAME", POSTGRES.getUsername());
        environment.put("PAYMENT_DB_PASSWORD", POSTGRES.getPassword());
        environment.put("PAYMENT_REDIS_HOST", VALKEY.getHost());
        environment.put("PAYMENT_REDIS_PORT", Integer.toString(VALKEY.getMappedPort(6379)));
        environment.put("PAYMENT_REDIS_PASSWORD", VALKEY_AUTH_VALUE);
        environment.put("PAYMENT_COOKIE_SECURE", "false");
        environment.put("PAYMENT_FLYWAY_ENABLED", "false");
        environment.putAll(rootEnvironment);
        PROCESSES.add(builder.start());
    }

    private static void waitUntilReady(Root root) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
        Throwable lastFailure = null;
        while (System.nanoTime() < deadline) {
            try {
                Response response = get(root, "/api/health", null);
                if (response.status() == 200 && response.body().contains("UP")) {
                    return;
                }
            } catch (IOException | InterruptedException failure) {
                lastFailure = failure;
                if (failure instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    throw failure;
                }
            }
            Thread.sleep(100L);
        }
        throw new IllegalStateException(root.name() + " did not become ready", lastFailure);
    }

    private static Response login(Root root, String username, String password, String extraField)
        throws Exception {
        String body = "{" + (extraField == null ? "" : extraField)
            + "\"username\":\"" + username + "\",\"password\":\"" + password + "\"}";
        return send(root, "POST", "/api/auth/login", null, root.origin(), body);
    }

    private static Response get(Root root, String path, String cookie) throws Exception {
        return send(root, "GET", path, cookie, null, null);
    }

    private static Response send(Root root, String method, String path, String cookie,
                                 String origin, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + root.port() + path))
            .timeout(Duration.ofSeconds(5));
        if (cookie != null) {
            request.header("Cookie", cookie);
        }
        if (origin != null) {
            request.header("Origin", origin);
        }
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), response.body(), response.headers().map());
    }

    private static Root root(String name) {
        return roots.stream().filter(candidate -> candidate.name().equals(name)).findFirst().orElseThrow();
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static void update(String sql, Object... parameters) throws SQLException {
        try (Connection connection = connection()) {
            execute(connection, sql, parameters);
        }
    }

    private static String queryString(String sql, Object... parameters) throws SQLException {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getString(1);
            }
        }
    }

    private static void inTransaction(SqlAction action) throws SQLException {
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try {
                action.execute(connection);
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    private static void execute(Connection connection, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
    }

    private static void bind(PreparedStatement statement, Object... parameters) throws SQLException {
        for (int index = 0; index < parameters.length; index++) {
            statement.setObject(index + 1, parameters[index]);
        }
    }

    @FunctionalInterface
    private interface SqlAction {
        void execute(Connection connection) throws SQLException;
    }

    private record Root(String name, int port, String origin, String username,
                        String cookieName, long membershipId, String loginType) { }

    private record Observation(boolean afterCommit, int status) { }

    private record Response(int status, String body, Map<String, List<String>> headers) {
        Map<String, String> cookies() {
            Map<String, String> values = new LinkedHashMap<>();
            for (String header : headers.getOrDefault("set-cookie", List.of())) {
                String pair = header.substring(0, header.indexOf(';'));
                values.put(pair.substring(0, pair.indexOf('=')), pair);
            }
            return values;
        }

        String cookie(String name) {
            return cookies().get(name);
        }

        String firstHeader(String name) {
            return headers.getOrDefault(name.toLowerCase(), List.of()).stream().findFirst().orElse("");
        }
    }
}
