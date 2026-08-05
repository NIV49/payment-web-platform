package com.niv.payment.adminapi;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class GrantDimensionModeCompatibilityMigrationTest {
    private static final long TENANT_ID = 9_210_000L;
    private static final long ROLE_ID = 9_210_001L;
    private static final long PERMISSION_ID = 9_210_002L;
    private static final long GRANT_ID = 9_210_003L;
    private static final long DIMENSION_ID = 9_210_004L;
    private static final String CONSTRAINT_NAME = "ck_iam_grant_dimension_mode_compatibility";

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

    @Test
    void malformedLegacyDimensionBlocksV10WithoutHidingTheRowForManualRepair() throws Exception {
        migrateTo("9");
        seedLegacyGrant("SELF");

        assertThat(currentVersion()).isEqualTo("9");
        assertThat(scopeMode()).isEqualTo("SELF");

        assertThatThrownBy(GrantDimensionModeCompatibilityMigrationTest::migrateToLatest)
            .hasStackTraceContaining(CONSTRAINT_NAME);

        assertThat(currentVersion()).isEqualTo("9");
        assertThat(constraintCount()).isZero();
        assertThat(scopeMode()).isEqualTo("SELF");
        assertThat(singleLong("SELECT count(*) FROM iam_role_grant WHERE id = " + GRANT_ID)).isOne();
    }

    @Test
    void validLegacyDimensionUpgradesThroughV10AndKeepsTheGrant() throws Exception {
        migrateTo("9");
        seedLegacyGrant("TENANT_ALL");

        migrateTo("10");

        assertThat(currentVersion()).isEqualTo("10");
        assertThat(constraintCount()).isOne();
        assertThat(constraintValidated()).isTrue();
        assertThat(scopeMode()).isEqualTo("TENANT_ALL");
        assertThat(singleLong("SELECT count(*) FROM iam_role_grant WHERE id = " + GRANT_ID)).isOne();
    }

    private static void seedLegacyGrant(String scopeMode) throws Exception {
        executeUpdate("""
            INSERT INTO iam_tenant(id, tenant_code, tenant_name, tenant_type, status)
            VALUES (%d, 'v10-upgrade', 'V10 Upgrade', 'PLATFORM', 'ACTIVE');

            INSERT INTO iam_role(
                id, tenant_id, role_code, role_name, applicable_tenant_type,
                assignable, system_role, status
            ) VALUES (%d, %d, 'v10-upgrade', 'V10 Upgrade', 'PLATFORM', true, false, 'ACTIVE');

            INSERT INTO iam_permission(
                id, permission_code, resource_code, action_code, risk_level,
                required_dimensions, requires_step_up, requires_approval, status,
                cross_tenant_mode
            ) VALUES (
                %d, 'migration-scope:read', 'migration-scope', 'read', 'NORMAL',
                ARRAY['TENANT']::varchar(32)[], false, false, 'ACTIVE',
                'SAME_TENANT_ONLY'
            );

            INSERT INTO iam_role_grant(
                id, tenant_id, role_id, permission_id, grant_key, status
            ) VALUES (%d, %d, %d, %d, 'v10_upgrade', 'ACTIVE');

            INSERT INTO iam_grant_dimension(id, grant_id, dimension_code, scope_mode)
            VALUES (%d, %d, 'TENANT', '%s');
            """.formatted(
                TENANT_ID,
                ROLE_ID, TENANT_ID,
                PERMISSION_ID,
                GRANT_ID, TENANT_ID, ROLE_ID, PERMISSION_ID,
                DIMENSION_ID, GRANT_ID, scopeMode));
    }

    private static void migrateToLatest() {
        flyway(null).migrate();
    }

    private static void migrateTo(String version) {
        flyway(version).migrate();
    }

    private static Flyway flyway(String version) {
        var configuration = PostgresFlywayTestSupport.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration")
            .cleanDisabled(false);
        if (version != null) {
            configuration.target(version);
        }
        return configuration.load();
    }

    private static String currentVersion() throws Exception {
        return singleString("""
            SELECT version
              FROM flyway_schema_history
             WHERE success
             ORDER BY installed_rank DESC
             LIMIT 1
            """);
    }

    private static long constraintCount() throws Exception {
        return singleLong("""
            SELECT count(*)
              FROM pg_constraint
             WHERE conrelid = 'iam_grant_dimension'::regclass
               AND conname = 'ck_iam_grant_dimension_mode_compatibility'
            """);
    }

    private static boolean constraintValidated() throws Exception {
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                 SELECT convalidated
                   FROM pg_constraint
                  WHERE conrelid = 'iam_grant_dimension'::regclass
                    AND conname = 'ck_iam_grant_dimension_mode_compatibility'
                 """)) {
            result.next();
            return result.getBoolean(1);
        }
    }

    private static String scopeMode() throws Exception {
        return singleString("SELECT scope_mode FROM iam_grant_dimension WHERE id = " + DIMENSION_ID);
    }

    private static long singleLong(String sql) throws Exception {
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }

    private static String singleString(String sql) throws Exception {
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }

    private static void executeUpdate(String sql) throws Exception {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private static Connection connection() throws Exception {
        return DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
