package com.niv.payment.adminapi;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class MenuExternalNavigationSafetyMigrationTest {
    private static final long TENANT_ID = 9_410_000L;

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse(
        "postgres:18.4-alpine@sha256:9a8afca54e7861fd90fab5fdf4c42477a6b1cb7d293595148e674e0a3181de15")
        .asCompatibleSubstituteFor("postgres"))
        .withDatabaseName("payment_platform")
        .withUsername("payment_dev")
        .withPassword("payment_dev");

    @BeforeEach
    void cleanDatabase() {
        flyway(null).clean();
    }

    @ParameterizedTest(name = "V11 rejects historical {0} metadata atomically")
    @MethodSource("unsafeHistoricalMetadata")
    void unsafeHistoricalMetadataRejectsV11Atomically(String scenario, String menuType,
                                                       String metaJson) throws Exception {
        migrateTo("10");
        insertTenant();
        insertMenu(9_410_101L, menuType, metaJson);

        assertThatThrownBy(MenuExternalNavigationSafetyMigrationTest::migrateToLatest)
            .hasStackTraceContaining("ck_iam_menu_external_navigation_safety");

        assertThat(currentSuccessfulVersion()).isEqualTo("10");
        assertThat(constraintCount()).isZero();
        assertThat(singleLong("SELECT count(*) FROM iam_menu WHERE tenant_id = 9410000"))
            .isOne();
    }

    @Test
    void validHttpAndHttpsRowsUpgradeAndConstraintRejectsLaterUnsafeWrites() throws Exception {
        migrateTo("10");
        insertTenant();
        insertMenu(9_410_201L, "EMBEDDED",
            "{\"title\":\"page.status.title\",\"iframeSrc\":\"https://status.example.com/health?full=true\"}");
        insertMenu(9_410_202L, "LINK",
            "{\"title\":\"page.docs.title\",\"link\":\"http://docs.example.com/start\"}");

        migrateTo("11");

        assertThat(currentSuccessfulVersion()).isEqualTo("11");
        assertThat(constraintCount()).isOne();
        assertThatThrownBy(() -> insertMenu(9_410_203L, "PAGE",
            "{\"title\":\"page.unsafe.title\",\"link\":\"https://evil.example\"}"))
            .hasStackTraceContaining("ck_iam_menu_external_navigation_safety");
    }

    private static Stream<Arguments> unsafeHistoricalMetadata() {
        return Stream.of(
            Arguments.of("javascript iframe", "EMBEDDED",
                "{\"title\":\"page.bad.title\",\"iframeSrc\":\"javascript:alert(1)\"}"),
            Arguments.of("data link", "LINK",
                "{\"title\":\"page.bad.title\",\"link\":\"data:text/html,pwned\"}"),
            Arguments.of("file link", "LINK",
                "{\"title\":\"page.bad.title\",\"link\":\"file:///etc/passwd\"}"),
            Arguments.of("protocol-relative link", "LINK",
                "{\"title\":\"page.bad.title\",\"link\":\"//evil.example\"}"),
            Arguments.of("https URL without host", "LINK",
                "{\"title\":\"page.bad.title\",\"link\":\"https:///missing-host\"}"),
            Arguments.of("non-string iframe", "EMBEDDED",
                "{\"title\":\"page.bad.title\",\"iframeSrc\":42}"),
            Arguments.of("non-string link", "LINK",
                "{\"title\":\"page.bad.title\",\"link\":{\"url\":\"https://evil.example\"}}"),
            Arguments.of("external field on page", "PAGE",
                "{\"title\":\"page.bad.title\",\"link\":\"https://evil.example\"}"),
            Arguments.of("mutually exclusive fields", "EMBEDDED",
                "{\"title\":\"page.bad.title\",\"iframeSrc\":\"https://safe.example\","
                    + "\"link\":\"https://evil.example\"}")
        );
    }

    private static void insertTenant() throws Exception {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("""
                 INSERT INTO iam_tenant(id, tenant_code, tenant_name, tenant_type, status)
                 VALUES (?, 'menu-safety-migration', 'Menu safety migration', 'PLATFORM', 'ACTIVE')
                 """)) {
            statement.setLong(1, TENANT_ID);
            statement.executeUpdate();
        }
    }

    private static void insertMenu(long menuId, String menuType, String metaJson) throws Exception {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("""
                 INSERT INTO iam_menu(
                     id, tenant_id, parent_id, menu_type, menu_name, route_name, route_path,
                     component_path, sort_order, status, meta_json
                 ) VALUES (?, ?, NULL, ?, ?, ?, ?, ?, 999, 'ACTIVE', ?::jsonb)
                 """)) {
            statement.setLong(1, menuId);
            statement.setLong(2, TENANT_ID);
            statement.setString(3, menuType);
            statement.setString(4, "Migration menu " + menuId);
            statement.setString(5, "MigrationMenu" + menuId);
            statement.setString(6, "/migration-menu-" + menuId);
            statement.setString(7, switch (menuType) {
                case "EMBEDDED", "LINK" -> "IFrameView";
                case "PAGE" -> "/system/user/list";
                default -> null;
            });
            statement.setString(8, metaJson);
            statement.executeUpdate();
        }
    }

    private static String currentSuccessfulVersion() throws Exception {
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                 SELECT version
                   FROM flyway_schema_history
                  WHERE success
                  ORDER BY installed_rank DESC
                  LIMIT 1
                 """)) {
            result.next();
            return result.getString(1);
        }
    }

    private static long constraintCount() throws Exception {
        return singleLong("""
            SELECT count(*)
              FROM pg_constraint
             WHERE conname = 'ck_iam_menu_external_navigation_safety'
            """);
    }

    private static long singleLong(String sql) throws Exception {
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }

    private static void migrateToLatest() {
        flyway(null).migrate();
    }

    private static void migrateTo(String version) {
        flyway(version).migrate();
    }

    private static Flyway flyway(String version) {
        var configuration = Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration")
            .cleanDisabled(false);
        if (version != null) configuration.target(version);
        return configuration.load();
    }

    private static Connection connection() throws Exception {
        return DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
