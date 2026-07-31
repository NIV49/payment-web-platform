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

@Testcontainers
class SoftDeleteFoundationMigrationTest {
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
    void v17AddsTombstonesProtectsBootstrapRowsAndScopesUniqueKeysToLiveRows() throws Exception {
        flyway("16").migrate();
        execute("""
            INSERT INTO iam_tenant(id, tenant_code, tenant_name, tenant_type, status)
            VALUES (1, 'platform', 'Platform', 'PLATFORM', 'ACTIVE');
            INSERT INTO iam_department(
                id, tenant_id, department_code, department_name, status
            ) VALUES (10, 1, 'head-office', 'Head Office', 'ACTIVE');
            INSERT INTO iam_menu(
                id, tenant_id, menu_type, menu_name, route_name, route_path,
                component_path, status, meta_json
            ) VALUES (
                6000, 1, 'PAGE', 'System', 'System', '/system',
                '/system/index', 'ACTIVE', '{}'::jsonb
            );
            """);

        flyway(null).migrate();

        assertThat(singleLong("""
            SELECT count(*)
              FROM information_schema.columns
             WHERE table_schema = 'public'
               AND ((table_name = 'iam_role' AND column_name = 'deleted_at')
                 OR (table_name IN ('iam_department', 'iam_menu')
                     AND column_name IN ('deleted_at', 'system_managed')))
            """)).isEqualTo(5L);
        assertThat(singleLong("""
            SELECT count(*) FROM iam_department
             WHERE id = 10 AND system_managed AND deleted_at IS NULL
            """)).isOne();
        assertThat(singleLong("""
            SELECT count(*) FROM iam_menu
             WHERE id = 6000 AND system_managed AND deleted_at IS NULL
            """)).isOne();

        execute("""
            INSERT INTO iam_role(
                id, tenant_id, role_code, role_name, applicable_tenant_type,
                assignable, system_role, status
            ) VALUES (7101, 1, 'first-role', 'Reusable Role', 'PLATFORM', true, false, 'ACTIVE');
            UPDATE iam_role SET status = 'DISABLED', deleted_at = now() WHERE id = 7101;
            INSERT INTO iam_role(
                id, tenant_id, role_code, role_name, applicable_tenant_type,
                assignable, system_role, status
            ) VALUES (7102, 1, 'second-role', 'Reusable Role', 'PLATFORM', true, false, 'ACTIVE');
            UPDATE iam_menu SET status = 'DISABLED', deleted_at = now() WHERE id = 6000;
            INSERT INTO iam_menu(
                id, tenant_id, menu_type, menu_name, route_name, route_path,
                component_path, status, meta_json
            ) VALUES (
                7103, 1, 'PAGE', 'System replacement', 'System', '/system',
                '/system/replacement', 'ACTIVE', '{}'::jsonb
            );
            """);

        assertThat(singleLong("SELECT count(*) FROM iam_role WHERE role_name = 'Reusable Role'"))
            .isEqualTo(2L);
        assertThat(singleLong("SELECT count(*) FROM iam_menu WHERE route_path = '/system'"))
            .isEqualTo(2L);
    }

    private static void execute(String sql) throws Exception {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
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

    private static Flyway flyway(String target) {
        var configuration = Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration")
            .cleanDisabled(false);
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    private static Connection connection() throws Exception {
        return DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
