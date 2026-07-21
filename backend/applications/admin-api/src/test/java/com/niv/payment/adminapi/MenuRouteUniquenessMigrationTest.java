package com.niv.payment.adminapi;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class MenuRouteUniquenessMigrationTest {
    private static final long TENANT_A = 9_310_000L;
    private static final long TENANT_B = 9_320_000L;

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

    @ParameterizedTest(name = "V9 atomically rejects historical {0} duplicates")
    @EnumSource(DuplicateKind.class)
    void sameTenantHistoricalDuplicatesRejectV9Atomically(DuplicateKind duplicate) throws Exception {
        migrateTo("8");
        insertTenant(TENANT_A, "route-migration-a");
        insertMenu(9_310_101L, TENANT_A, "FirstRoute", "/first-route");
        insertMenu(
            9_310_102L,
            TENANT_A,
            duplicate == DuplicateKind.ROUTE_NAME ? "firstroute" : "SecondRoute",
            duplicate == DuplicateKind.ROUTE_PATH ? "/first-route" : "/second-route");

        assertThatThrownBy(MenuRouteUniquenessMigrationTest::migrateToLatest)
            .hasStackTraceContaining(duplicate.failureMessage);

        assertThat(currentSuccessfulVersion()).isEqualTo("8");
        assertThat(singleLong("""
            SELECT count(*) FROM flyway_schema_history WHERE version = '9'
            """)).isZero();
        assertThat(menuRouteUniqueIndexCount()).isZero();
        assertThat(singleLong("""
            SELECT count(*) FROM iam_menu WHERE tenant_id = 9310000
            """)).isEqualTo(2L);
    }

    @Test
    void differentTenantsMayReuseRouteNameAndPathDuringV9Upgrade() throws Exception {
        migrateTo("8");
        insertTenant(TENANT_A, "route-migration-a");
        insertTenant(TENANT_B, "route-migration-b");
        insertMenu(9_310_201L, TENANT_A, "SharedRoute", "/Shared-Route///");
        insertMenu(9_320_201L, TENANT_B, "sharedroute", "/shared-route");

        migrateToLatest();

        assertThat(singleLong("""
            SELECT count(*) FROM flyway_schema_history WHERE version = '9' AND success
            """)).isOne();
        assertThat(menuRouteUniqueIndexCount()).isEqualTo(2L);
        assertThat(singleLong("""
            SELECT count(*) FROM iam_menu
             WHERE lower(route_name) = 'sharedroute'
            """)).isEqualTo(2L);
    }

    @Test
    void canonicalHistoricalPathDuplicatesRejectV9Atomically() throws Exception {
        migrateTo("8");
        insertTenant(TENANT_A, "canonical-route-migration");
        insertMenu(9_310_301L, TENANT_A, "FirstCanonicalPath", "/Checkout///");
        insertMenu(9_310_302L, TENANT_A, "SecondCanonicalPath", "/checkout");

        assertThatThrownBy(MenuRouteUniquenessMigrationTest::migrateToLatest)
            .hasStackTraceContaining("V9 menu route uniqueness refused: duplicate route paths exist");

        assertThat(currentSuccessfulVersion()).isEqualTo("8");
        assertThat(menuRouteUniqueIndexCount()).isZero();
        assertThat(singleLong("SELECT count(*) FROM iam_menu WHERE tenant_id = 9310000"))
            .isEqualTo(2L);
    }

    @Test
    void blankHistoricalRouteNameFallsBackToMenuNameAtomically() throws Exception {
        migrateTo("8");
        insertTenant(TENANT_A, "blank-name-migration");
        insertMenu(9_310_401L, TENANT_A, "FallbackRoute", "   ", "/fallback-route");
        insertMenu(9_310_402L, TENANT_A, "OtherMenu", "fallbackroute", "/other-route");

        assertThatThrownBy(MenuRouteUniquenessMigrationTest::migrateToLatest)
            .hasStackTraceContaining("V9 menu route uniqueness refused: duplicate route names exist");

        assertThat(currentSuccessfulVersion()).isEqualTo("8");
        assertThat(menuRouteUniqueIndexCount()).isZero();
    }

    @Test
    void databaseConstraintRejectsCanonicalPathDuplicatesAfterV9() throws Exception {
        migrateToLatest();
        insertTenant(TENANT_A, "post-v9-route-constraint");
        insertMenu(9_310_501L, TENANT_A, "FirstPostV9Path", "/Reports///");

        assertThatThrownBy(() -> insertMenu(
            9_310_502L, TENANT_A, "SecondPostV9Path", "/reports"))
            .hasStackTraceContaining("uk_iam_menu_tenant_route_path");
    }

    private static void insertTenant(long tenantId, String tenantCode) throws Exception {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("""
                 INSERT INTO iam_tenant(id, tenant_code, tenant_name, tenant_type, status)
                 VALUES (?, ?, ?, 'PLATFORM', 'ACTIVE')
                 """)) {
            statement.setLong(1, tenantId);
            statement.setString(2, tenantCode);
            statement.setString(3, tenantCode);
            statement.executeUpdate();
        }
    }

    private static void insertMenu(long menuId, long tenantId, String routeName,
                                   String routePath) throws Exception {
        insertMenu(menuId, tenantId, routeName, routeName, routePath);
    }

    private static void insertMenu(long menuId, long tenantId, String menuName,
                                   String routeName, String routePath) throws Exception {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("""
                 INSERT INTO iam_menu(
                     id, tenant_id, parent_id, menu_type, menu_name,
                     route_name, route_path, component_path, sort_order, status, meta_json
                 ) VALUES (?, ?, NULL, 'PAGE', ?, ?, ?, '/migration-test', 999, 'ACTIVE', '{}'::jsonb)
                 """)) {
            statement.setLong(1, menuId);
            statement.setLong(2, tenantId);
            statement.setString(3, menuName);
            statement.setString(4, routeName);
            statement.setString(5, routePath);
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

    private static long menuRouteUniqueIndexCount() throws Exception {
        return singleLong("""
            SELECT count(*)
              FROM pg_indexes
             WHERE schemaname = 'public'
               AND indexname IN (
                   'uk_iam_menu_tenant_route_name_ci',
                   'uk_iam_menu_tenant_route_path'
               )
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
        if (version != null) {
            configuration.target(version);
        }
        return configuration.load();
    }

    private static Connection connection() throws Exception {
        return DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private enum DuplicateKind {
        ROUTE_NAME("V9 menu route uniqueness refused: duplicate route names exist"),
        ROUTE_PATH("V9 menu route uniqueness refused: duplicate route paths exist");

        private final String failureMessage;

        DuplicateKind(String failureMessage) {
            this.failureMessage = failureMessage;
        }
    }
}
