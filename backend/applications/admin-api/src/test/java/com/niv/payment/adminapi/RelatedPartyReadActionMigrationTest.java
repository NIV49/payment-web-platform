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
class RelatedPartyReadActionMigrationTest {
    private static final String CONSTRAINT_NAME =
        "ck_iam_permission_related_party_read_action";

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
    void historicalMutationPermissionBlocksV12Atomically() throws Exception {
        migrateTo("11");
        insertPermission(9_412_001L, "merchant:update", "merchant", "update",
            "RELATED_PARTY_READ");

        assertThatThrownBy(RelatedPartyReadActionMigrationTest::migrateToLatest)
            .hasStackTraceContaining(CONSTRAINT_NAME);

        assertThat(currentSuccessfulVersion()).isEqualTo("11");
        assertThat(constraintCount()).isZero();
        assertThat(singleLong("SELECT count(*) FROM iam_permission WHERE id = 9412001"))
            .isOne();
    }

    @Test
    void readActionsUpgradeAndConstraintRejectsLaterMutationWrites() throws Exception {
        migrateTo("11");
        insertPermission(9_412_101L, "order:view", "order", "view", "RELATED_PARTY_READ");
        insertPermission(9_412_102L, "order:read", "order", "read", "RELATED_PARTY_READ");

        migrateToLatest();

        assertThat(currentSuccessfulVersion()).isEqualTo("16");
        assertThat(constraintCount()).isOne();
        assertThat(constraintValidated()).isTrue();
        assertThatThrownBy(() -> insertPermission(
            9_412_103L, "order:update", "order", "update", "RELATED_PARTY_READ"))
            .hasStackTraceContaining(CONSTRAINT_NAME);
    }

    private static void insertPermission(long id, String code, String resource, String action,
                                         String crossTenantMode) throws Exception {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                INSERT INTO iam_permission(
                    id, permission_code, resource_code, action_code, risk_level,
                    required_dimensions, requires_step_up, requires_approval, status,
                    cross_tenant_mode
                ) VALUES (
                    %d, '%s', '%s', '%s', 'NORMAL', ARRAY['MERCHANT']::varchar(32)[],
                    false, false, 'ACTIVE', '%s'
                )
                """.formatted(id, code, resource, action, crossTenantMode));
        }
    }

    private static String currentSuccessfulVersion() throws Exception {
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                 SELECT version FROM flyway_schema_history
                  WHERE success ORDER BY installed_rank DESC LIMIT 1
                 """)) {
            result.next();
            return result.getString(1);
        }
    }

    private static long constraintCount() throws Exception {
        return singleLong("""
            SELECT count(*) FROM pg_constraint
             WHERE conrelid = 'iam_permission'::regclass AND conname = '%s'
            """.formatted(CONSTRAINT_NAME));
    }

    private static boolean constraintValidated() throws Exception {
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                 SELECT convalidated FROM pg_constraint
                  WHERE conrelid = 'iam_permission'::regclass AND conname = '%s'
                 """.formatted(CONSTRAINT_NAME))) {
            result.next();
            return result.getBoolean(1);
        }
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
}
