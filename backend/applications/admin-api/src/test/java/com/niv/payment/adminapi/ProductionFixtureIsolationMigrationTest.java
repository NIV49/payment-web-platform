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
class ProductionFixtureIsolationMigrationTest {
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
    void cleanProductionMigrationKeepsTheProductCatalogButRemovesTheLocalFixture() throws Exception {
        migrateToLatest();

        assertThat(rowCount("iam_permission")).isEqualTo(14);
        assertThat(rowCount("iam_tenant")).isZero();
        assertThat(rowCount("iam_department")).isZero();
        assertThat(rowCount("iam_user")).isZero();
        assertThat(rowCount("iam_membership")).isZero();
        assertThat(rowCount("iam_authentication_credential")).isZero();
        assertThat(rowCount("iam_role")).isZero();
        assertThat(rowCount("iam_membership_role")).isZero();
        assertThat(rowCount("iam_role_grant")).isZero();
        assertThat(rowCount("iam_grant_dimension")).isZero();
        assertThat(rowCount("iam_menu")).isZero();
        assertThat(rowCount("iam_role_menu")).isZero();
    }

    @Test
    void exactFixtureIsRemovedWhileUnrelatedProductionRowsAndCatalogExtensionsSurvive() throws Exception {
        migrateTo("7");
        insertUnrelatedProductionData();
        insertPermissionExtension();

        migrateToLatest();

        assertThat(singleLong("SELECT count(*) FROM iam_tenant WHERE id = 1")).isZero();
        assertThat(singleLong("SELECT count(*) FROM iam_tenant WHERE id = 2 AND tenant_code = 'merchant-two'"))
            .isOne();
        assertThat(singleLong("SELECT count(*) FROM iam_user WHERE id = 200 AND idp_subject = 'real-user'"))
            .isOne();
        assertThat(singleLong("SELECT count(*) FROM iam_audit_event WHERE trace_id = 'real-audit'"))
            .isOne();
        assertThat(singleLong("SELECT count(*) FROM iam_permission_change_outbox WHERE trace_id = 'real-outbox'"))
            .isOne();
        assertThat(singleLong("SELECT count(*) FROM iam_permission WHERE id = 9001 AND permission_code = 'payout:view'"))
            .isOne();
        assertThat(rowCount("iam_permission")).isEqualTo(15);
    }

    @Test
    void noFixtureFootprintIsANoOpForUnrelatedProductionData() throws Exception {
        migrateTo("7");
        deleteExactFixture();
        insertUnrelatedProductionData();
        insertPermissionExtension();

        migrateToLatest();

        assertThat(rowCount("iam_tenant")).isOne();
        assertThat(rowCount("iam_user")).isOne();
        assertThat(rowCount("iam_audit_event")).isOne();
        assertThat(rowCount("iam_permission_change_outbox")).isOne();
        assertThat(rowCount("iam_permission_change_relay_state")).isOne();
        assertThat(rowCount("iam_permission")).isEqualTo(15);
    }

    @Test
    void modifiedFixtureFailsAtomically() throws Exception {
        migrateTo("7");
        executeUpdate("UPDATE iam_tenant SET tenant_name = 'Modified fixture' WHERE id = 1");

        assertThatThrownBy(ProductionFixtureIsolationMigrationTest::migrateToLatest)
            .hasStackTraceContaining("fixture footprint is incomplete or modified");

        assertThat(singleLong("SELECT count(*) FROM iam_tenant WHERE id = 1 AND tenant_name = 'Modified fixture'"))
            .isOne();
        assertThat(rowCount("iam_role_grant")).isEqualTo(14);
        assertThat(rowCount("iam_menu")).isEqualTo(8);
    }

    @Test
    void reservedIdentifierCollisionFailsWithoutDeletingTheCollidingSubject() throws Exception {
        migrateTo("7");
        deleteExactFixture();
        executeUpdate("""
            INSERT INTO iam_tenant(id, tenant_code, tenant_name, tenant_type, status)
            VALUES (1, 'real-platform', 'Real Platform', 'PLATFORM', 'ACTIVE')
            """);

        assertThatThrownBy(ProductionFixtureIsolationMigrationTest::migrateToLatest)
            .hasStackTraceContaining("fixture footprint is incomplete or modified");

        assertThat(singleLong("SELECT count(*) FROM iam_tenant WHERE id = 1 AND tenant_code = 'real-platform'"))
            .isOne();
    }

    @Test
    void reservedNaturalKeyCollisionFailsWithoutDeletingTheCollidingSubject() throws Exception {
        migrateTo("7");
        deleteExactFixture();
        executeUpdate("""
            INSERT INTO iam_tenant(id, tenant_code, tenant_name, tenant_type, status)
            VALUES (2, 'platform', 'Real Platform', 'PLATFORM', 'ACTIVE')
            """);

        assertThatThrownBy(ProductionFixtureIsolationMigrationTest::migrateToLatest)
            .hasStackTraceContaining("fixture footprint is incomplete or modified");

        assertThat(singleLong("SELECT count(*) FROM iam_tenant WHERE id = 2 AND tenant_code = 'platform'"))
            .isOne();
    }

    @Test
    void partialFixtureFailsAtomically() throws Exception {
        migrateTo("7");
        executeUpdate("DELETE FROM iam_role_menu WHERE tenant_id = 1 AND role_id = 2000 AND menu_id = 6012");

        assertThatThrownBy(ProductionFixtureIsolationMigrationTest::migrateToLatest)
            .hasStackTraceContaining("fixture footprint is incomplete or modified");

        assertThat(rowCount("iam_tenant")).isOne();
        assertThat(rowCount("iam_role_menu")).isEqualTo(7);
    }

    @Test
    void extraTenantOneRelationshipFailsAtomically() throws Exception {
        migrateTo("7");
        executeUpdate("""
            INSERT INTO iam_department(
                id, tenant_id, parent_id, department_code, department_name, status, remark
            ) VALUES (11, 1, 10, 'extra', 'Extra', 'ACTIVE', 'real relationship')
            """);

        assertThatThrownBy(ProductionFixtureIsolationMigrationTest::migrateToLatest)
            .hasStackTraceContaining("fixture footprint is incomplete or modified");

        assertThat(rowCount("iam_department")).isEqualTo(2);
        assertThat(rowCount("iam_tenant")).isOne();
    }

    @Test
    void tenantOneAuditAndOutboxHistoryBlockFixtureDeletionAtomically() throws Exception {
        migrateTo("7");
        executeUpdate("""
            INSERT INTO iam_audit_event(
                id, tenant_id, target_type, target_ref, action_code,
                decision, reason_code, trace_id
            )
            VALUES (nextval('iam_id_seq'), 1, 'MIGRATION', 'local-fixture',
                    'FIXTURE_USED', 'NOT_APPLICABLE', 'TEST', 'fixture-audit')
            """);
        executeUpdate("""
            INSERT INTO iam_permission_change_outbox(
                id, tenant_id, aggregate_type, aggregate_ref, event_type, payload,
                aggregate_version, schema_version, partition_key, trace_id
            )
            VALUES (nextval('iam_id_seq'), 1, 'MEMBERSHIP', '1000',
                    'PermissionChanged', '{}'::jsonb, 1, 1, '1000', 'fixture-outbox')
            """);

        assertThatThrownBy(ProductionFixtureIsolationMigrationTest::migrateToLatest)
            .hasStackTraceContaining("fixture footprint is incomplete or modified");

        assertThat(rowCount("iam_audit_event")).isOne();
        assertThat(rowCount("iam_permission_change_outbox")).isOne();
        assertThat(rowCount("iam_permission_change_relay_state")).isOne();
        assertThat(rowCount("iam_tenant")).isOne();
    }

    @Test
    void missingRequiredCatalogPermissionBlocksMigrationWithoutCreatingOrDeletingIdentityData() throws Exception {
        migrateTo("7");
        deleteExactFixture();
        executeUpdate("DELETE FROM iam_permission WHERE id = 3001");
        insertUnrelatedProductionData();

        assertThatThrownBy(ProductionFixtureIsolationMigrationTest::migrateToLatest)
            .hasStackTraceContaining("required permission catalog is incomplete or modified");

        assertThat(rowCount("iam_permission")).isEqualTo(13);
        assertThat(rowCount("iam_tenant")).isOne();
        assertThat(rowCount("iam_audit_event")).isOne();
    }

    @Test
    void modifiedRequiredCatalogPermissionBlocksMigration() throws Exception {
        migrateTo("7");
        executeUpdate("UPDATE iam_permission SET description = 'Modified' WHERE id = 3001");

        assertThatThrownBy(ProductionFixtureIsolationMigrationTest::migrateToLatest)
            .hasStackTraceContaining("required permission catalog is incomplete or modified");

        assertThat(singleLong("SELECT count(*) FROM iam_permission WHERE id = 3001 AND description = 'Modified'"))
            .isOne();
        assertThat(rowCount("iam_tenant")).isOne();
    }

    private static void insertUnrelatedProductionData() throws Exception {
        executeUpdate("""
            INSERT INTO iam_tenant(id, tenant_code, tenant_name, tenant_type, status)
            VALUES (2, 'merchant-two', 'Merchant Two', 'DIRECT_MERCHANT', 'ACTIVE')
            """);
        executeUpdate("""
            INSERT INTO iam_user(id, idp_issuer, idp_subject, display_name, status)
            VALUES (200, 'production-idp', 'real-user', 'Real User', 'ACTIVE')
            """);
        executeUpdate("""
            INSERT INTO iam_audit_event(
                id, tenant_id, target_type, target_ref, action_code,
                decision, reason_code, trace_id
            ) VALUES (nextval('iam_id_seq'), 2, 'TENANT', '2', 'CREATED',
                      'NOT_APPLICABLE', 'TEST', 'real-audit')
            """);
        executeUpdate("""
            INSERT INTO iam_permission_change_outbox(
                id, tenant_id, aggregate_type, aggregate_ref, event_type, payload,
                aggregate_version, schema_version, partition_key, trace_id
            ) VALUES (nextval('iam_id_seq'), 2, 'TENANT', '2', 'TenantChanged',
                      '{}'::jsonb, 1, 1, '2', 'real-outbox')
            """);
    }

    private static void insertPermissionExtension() throws Exception {
        executeUpdate("""
            INSERT INTO iam_permission(
                id, permission_code, resource_code, action_code, risk_level,
                required_dimensions, requires_step_up, requires_approval, status,
                description, cross_tenant_mode
            ) VALUES (9001, 'payout:view', 'payout', 'view', 'NORMAL',
                      ARRAY['TENANT']::varchar(32)[], false, false, 'ACTIVE',
                      'Product extension', 'SAME_TENANT_ONLY')
            """);
    }

    private static void deleteExactFixture() throws Exception {
        executeUpdate("""
            DELETE FROM iam_role_menu WHERE tenant_id = 1;
            DELETE FROM iam_grant_dimension WHERE grant_id BETWEEN 4001 AND 4014;
            DELETE FROM iam_role_grant WHERE tenant_id = 1;
            DELETE FROM iam_membership_role WHERE tenant_id = 1;
            DELETE FROM iam_menu WHERE tenant_id = 1;
            DELETE FROM iam_role WHERE tenant_id = 1;
            DELETE FROM iam_authentication_credential WHERE user_id = 100;
            DELETE FROM iam_membership WHERE tenant_id = 1;
            DELETE FROM iam_department WHERE tenant_id = 1;
            DELETE FROM iam_user WHERE id = 100;
            DELETE FROM iam_tenant WHERE id = 1;
            """);
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

    private static long rowCount(String table) throws Exception {
        return singleLong("SELECT count(*) FROM " + table);
    }

    private static long singleLong(String sql) throws Exception {
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
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
