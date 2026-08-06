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

        assertThat(rowCount("iam_permission")).isEqualTo(24);
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
        assertThat(rowCount("iam_permission")).isEqualTo(25);
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
        assertThat(rowCount("iam_permission")).isEqualTo(25);
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

    @Test
    void granularPermissionMigrationPreservesLegacyRoleCapabilitiesAndLimitsGrantMaintenanceToSystemRole() throws Exception {
        migrateTo("13");
        executeUpdate("""
            INSERT INTO iam_tenant(id,tenant_code,tenant_name,tenant_type,status)
            VALUES (50,'migration-platform','Migration Platform','PLATFORM','ACTIVE')
            """);
        executeUpdate("""
            INSERT INTO iam_user(id,idp_issuer,idp_subject,display_name,status)
            VALUES (51,'test','system-actor','System Actor','ACTIVE'),
                   (52,'test','ordinary-actor','Ordinary Actor','ACTIVE')
            """);
        executeUpdate("""
            INSERT INTO iam_membership(id,tenant_id,user_id,status)
            VALUES (53,50,51,'ACTIVE'),(54,50,52,'ACTIVE')
            """);
        executeUpdate("""
            INSERT INTO iam_role(id,tenant_id,role_code,role_name,applicable_tenant_type,
                                 assignable,system_role,status)
            VALUES (55,50,'legacy-system-admin','Legacy System Admin','PLATFORM',false,true,'ACTIVE'),
                   (56,50,'legacy-ordinary-admin','Legacy Ordinary Admin','PLATFORM',true,false,'ACTIVE')
            """);
        executeUpdate("""
            INSERT INTO iam_membership_role(tenant_id,membership_id,role_id)
            VALUES (50,53,55),(50,54,56)
            """);
        executeUpdate("""
            INSERT INTO iam_role_grant(id,tenant_id,role_id,permission_id,grant_key,status)
            VALUES
              (5051,50,55,3007,'system-role-view','ACTIVE'),
              (5052,50,55,3012,'system-menu-manage','ACTIVE'),
              (5053,50,55,3014,'system-department-manage','ACTIVE'),
              (5061,50,56,3007,'ordinary-role-view','ACTIVE'),
              (5062,50,56,3012,'ordinary-menu-manage','ACTIVE'),
              (5063,50,56,3014,'ordinary-department-manage','ACTIVE')
            """);
        executeUpdate("""
            INSERT INTO iam_grant_dimension(id,grant_id,dimension_code,scope_mode)
            VALUES
              (6051,5051,'TENANT','TENANT_ALL'),(6052,5052,'TENANT','TENANT_ALL'),
              (6053,5053,'TENANT','TENANT_ALL'),(6061,5061,'TENANT','TENANT_ALL'),
              (6062,5062,'TENANT','TENANT_ALL'),(6063,5063,'TENANT','TENANT_ALL')
            """);

        migrateToLatest();

        assertThat(singleLong("""
            SELECT count(*) FROM iam_role_grant grant_row
              JOIN iam_permission permission ON permission.id=grant_row.permission_id
             WHERE grant_row.tenant_id=50 AND grant_row.role_id=55 AND grant_row.status='ACTIVE'
               AND permission.permission_code IN (
                 'menu:create','menu:update','menu:delete',
                 'department:create','department:update','department:delete','role:grant-update')
            """)).isEqualTo(7);
        assertThat(singleLong("""
            SELECT count(*) FROM iam_role_grant grant_row
              JOIN iam_permission permission ON permission.id=grant_row.permission_id
             WHERE grant_row.tenant_id=50 AND grant_row.role_id=56
               AND grant_row.status='ACTIVE'
               AND permission.permission_code IN (
                 'menu:create','menu:update','menu:delete',
                 'department:create','department:update','department:delete')
            """)).isEqualTo(6);
        assertThat(singleLong("""
            SELECT count(*) FROM iam_role_grant grant_row
              JOIN iam_permission permission ON permission.id=grant_row.permission_id
             WHERE grant_row.tenant_id=50 AND grant_row.role_id=56
               AND permission.permission_code='role:grant-update'
            """)).isZero();
        assertThat(singleLong("SELECT row_version FROM iam_role WHERE id=55")).isEqualTo(3);
        assertThat(singleLong("SELECT row_version FROM iam_role WHERE id=56")).isEqualTo(3);
        assertThat(singleLong("SELECT permission_version FROM iam_membership WHERE id=53")).isEqualTo(3);
        assertThat(singleLong("SELECT permission_version FROM iam_membership WHERE id=54")).isEqualTo(3);
        assertThat(singleLong("SELECT count(*) FROM iam_audit_event WHERE tenant_id=50 AND trace_id='migration-v14'"))
            .isEqualTo(2);
        assertThat(singleLong("SELECT count(*) FROM iam_permission_change_outbox WHERE tenant_id=50 AND trace_id='migration-v14'"))
            .isEqualTo(2);
        assertThat(singleLong("SELECT count(*) FROM iam_audit_event WHERE tenant_id=50 AND trace_id='migration-v15'"))
            .isEqualTo(2);
        assertThat(singleLong("SELECT count(*) FROM iam_permission_change_outbox WHERE tenant_id=50 AND trace_id='migration-v15'"))
            .isEqualTo(2);
        assertThat(singleLong("""
            SELECT count(*) FROM iam_permission
             WHERE permission_code IN ('menu:manage','department:manage') AND status='ACTIVE'
            """)).isEqualTo(2);
    }

    @Test
    void expandMigrationPreservesConstrainedLegacyGrantsAndReactivatesCompatibilityCodes() throws Exception {
        migrateTo("13");
        executeUpdate("""
            INSERT INTO iam_tenant(id,tenant_code,tenant_name,tenant_type,status)
            VALUES (90,'constrained-platform','Constrained Platform','PLATFORM','ACTIVE')
            """);
        executeUpdate("""
            INSERT INTO iam_department(id,tenant_id,department_code,department_name,status)
            VALUES (91,90,'security','Security','ACTIVE')
            """);
        executeUpdate("""
            INSERT INTO iam_user(id,idp_issuer,idp_subject,display_name,status)
            VALUES (92,'test','constrained-actor','Constrained Actor','ACTIVE')
            """);
        executeUpdate("""
            INSERT INTO iam_membership(id,tenant_id,user_id,department_id,status)
            VALUES (93,90,92,91,'ACTIVE')
            """);
        executeUpdate("""
            INSERT INTO iam_role(id,tenant_id,role_code,role_name,applicable_tenant_type,
                                 assignable,system_role,status)
            VALUES (94,90,'constrained-admin','Constrained Admin','PLATFORM',true,false,'ACTIVE')
            """);
        executeUpdate("""
            INSERT INTO iam_membership_role(tenant_id,membership_id,role_id,assigned_by)
            VALUES (90,93,94,93)
            """);
        executeUpdate("""
            INSERT INTO iam_role_grant(
                id,tenant_id,role_id,permission_id,grant_key,status,valid_from,valid_until,created_by,updated_by
            ) VALUES
              (9052,90,94,3012,'constrained-menu-manage','ACTIVE',
               '2026-01-01T00:00:00Z','2027-01-01T00:00:00Z',93,93),
              (9053,90,94,3014,'constrained-department-manage','ACTIVE',
               '2026-01-01T00:00:00Z','2027-01-01T00:00:00Z',93,93)
            """);
        executeUpdate("""
            INSERT INTO iam_grant_dimension(id,grant_id,dimension_code,scope_mode)
            VALUES
              (9152,9052,'TENANT','TENANT_ALL'),(9153,9052,'DEPARTMENT','SPECIFIED'),
              (9154,9053,'TENANT','TENANT_ALL'),(9155,9053,'DEPARTMENT','SPECIFIED')
            """);
        executeUpdate("""
            INSERT INTO iam_grant_target(id,dimension_id,target_ref)
            VALUES (9252,9153,'91'),(9253,9155,'91')
            """);

        migrateToLatest();

        assertThat(singleLong("""
            SELECT count(*) FROM iam_permission
             WHERE permission_code IN ('menu:manage','department:manage') AND status='ACTIVE'
            """)).isEqualTo(2);
        assertThat(singleLong("""
            SELECT count(*) FROM iam_role_grant grant_row
              JOIN iam_permission permission ON permission.id=grant_row.permission_id
             WHERE grant_row.tenant_id=90 AND grant_row.role_id=94 AND grant_row.status='ACTIVE'
               AND grant_row.grant_key LIKE 'v15-%'
               AND permission.permission_code IN (
                 'menu:create','menu:update','menu:delete',
                 'department:create','department:update','department:delete')
            """)).isEqualTo(6);
        assertThat(singleLong("""
            SELECT count(*) FROM iam_role_grant grant_row
             WHERE grant_row.id IN (9052,9053) AND grant_row.status='ACTIVE'
               AND grant_row.valid_from='2026-01-01T00:00:00Z'
               AND grant_row.valid_until='2027-01-01T00:00:00Z'
            """)).isEqualTo(2);
        assertThat(singleLong("""
            SELECT count(*) FROM iam_role_grant grant_row
             WHERE grant_row.tenant_id=90 AND grant_row.role_id=94 AND grant_row.grant_key LIKE 'v15-%'
               AND grant_row.valid_from='2026-01-01T00:00:00Z'
               AND grant_row.valid_until='2027-01-01T00:00:00Z'
               AND grant_row.created_by=93 AND grant_row.updated_by=93
            """)).isEqualTo(6);
        assertThat(singleLong("""
            SELECT count(*) FROM iam_role_grant grant_row
             WHERE grant_row.tenant_id=90 AND grant_row.role_id=94 AND grant_row.grant_key LIKE 'v15-%'
               AND (SELECT count(*) FROM iam_grant_dimension dimension_row
                     WHERE dimension_row.grant_id=grant_row.id)=2
               AND (SELECT count(*) FROM iam_grant_target target
                      JOIN iam_grant_dimension dimension_row ON dimension_row.id=target.dimension_id
                     WHERE dimension_row.grant_id=grant_row.id AND target.target_ref='91')=1
            """)).isEqualTo(6);
        assertThat(singleLong("SELECT row_version FROM iam_role WHERE id=94")).isEqualTo(2);
        assertThat(singleLong("SELECT permission_version FROM iam_membership WHERE id=93")).isEqualTo(2);
        assertThat(singleLong("SELECT count(*) FROM iam_audit_event WHERE tenant_id=90 AND trace_id='migration-v15'"))
            .isOne();
        assertThat(singleLong("""
            SELECT count(*) FROM iam_permission_change_outbox
             WHERE tenant_id=90 AND aggregate_ref='93' AND trace_id='migration-v15'
            """)).isOne();
    }

    @Test
    void exactAdministrationCatalogGuardRejectsV15FieldTamperingWithoutRepairingIt() throws Exception {
        migrateTo("15");
        executeUpdate("UPDATE iam_permission SET risk_level='SENSITIVE' WHERE id=3015");
        long auditCount = rowCount("iam_audit_event");
        long outboxCount = rowCount("iam_permission_change_outbox");

        assertThatThrownBy(ProductionFixtureIsolationMigrationTest::migrateToLatest)
            .hasStackTraceContaining("administration permission catalog is incomplete or modified");

        assertThat(singleLong("""
            SELECT count(*) FROM flyway_schema_history
             WHERE version='15' AND success
            """)).isOne();
        assertThat(singleLong("""
            SELECT count(*) FROM flyway_schema_history
             WHERE version='16' AND success
            """)).isZero();
        assertThat(singleLong(
            "SELECT count(*) FROM iam_permission WHERE id=3015 AND risk_level='SENSITIVE'"))
            .isOne();
        assertThat(rowCount("iam_audit_event")).isEqualTo(auditCount);
        assertThat(rowCount("iam_permission_change_outbox")).isEqualTo(outboxCount);
    }

    @Test
    void granularPermissionMigrationRejectsAChangedLegacyCatalogWithoutCreatingNewPermissions() throws Exception {
        migrateTo("13");
        executeUpdate("UPDATE iam_permission SET status='DISABLED' WHERE id=3012");

        assertThatThrownBy(ProductionFixtureIsolationMigrationTest::migrateToLatest)
            .hasStackTraceContaining("requires the exact legacy catalog");

        assertThat(singleLong("SELECT count(*) FROM iam_permission WHERE id BETWEEN 3015 AND 3021"))
            .isZero();
        assertThat(singleLong("SELECT count(*) FROM iam_permission WHERE id=3012 AND status='DISABLED'"))
            .isOne();
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
            INSERT INTO iam_membership(id, tenant_id, user_id, status)
            VALUES (2000, 2, 200, 'ACTIVE')
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
        var configuration = PostgresFlywayTestSupport.configure()
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
