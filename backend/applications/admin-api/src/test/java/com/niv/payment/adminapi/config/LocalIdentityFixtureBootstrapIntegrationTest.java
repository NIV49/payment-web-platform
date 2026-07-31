package com.niv.payment.adminapi.config;

import com.niv.payment.permission.persistence.repository.JooqCredentialRepository;
import org.flywaydb.core.Flyway;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class LocalIdentityFixtureBootstrapIntegrationTest {
    private static final String FIXTURE_LOGIN_INPUT = "local-test-password";
    private static final String[] LEGACY_FIXTURE_TABLES = {
        "iam_tenant",
        "iam_department",
        "iam_user",
        "iam_membership",
        "iam_authentication_credential",
        "iam_role",
        "iam_membership_role",
        "iam_role_grant",
        "iam_grant_dimension",
        "iam_menu",
        "iam_role_menu"
    };

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse(
        "postgres:18.4-alpine@sha256:9a8afca54e7861fd90fab5fdf4c42477a6b1cb7d293595148e674e0a3181de15")
        .asCompatibleSubstituteFor("postgres"))
        .withDatabaseName("payment_platform")
        .withUsername("payment_dev")
        .withPassword("payment_dev");

    private DataSource dataSource;
    private JdbcTemplate jdbc;

    @BeforeEach
    void migrateProductionSchema() {
        Flyway flyway = Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration")
            .cleanDisabled(false)
            .load();
        flyway.clean();
        flyway.migrate();

        DriverManagerDataSource configuredDataSource = new DriverManagerDataSource();
        configuredDataSource.setDriverClassName("org.postgresql.Driver");
        configuredDataSource.setUrl(POSTGRES.getJdbcUrl());
        configuredDataSource.setUsername(POSTGRES.getUsername());
        configuredDataSource.setPassword(POSTGRES.getPassword());
        dataSource = configuredDataSource;
        jdbc = new JdbcTemplate(dataSource);
    }

    @Test
    void emptyDatabaseCreatesAndValidatesTheCompleteLocalFixture() throws Exception {
        runBootstrap(FIXTURE_LOGIN_INPUT);

        assertCompleteFixture();
        String hash = jdbc.queryForObject(
            "SELECT password_hash FROM iam_authentication_credential WHERE user_id = 100",
            String.class);
        assertThat(new BCryptPasswordEncoder(10).matches(FIXTURE_LOGIN_INPUT, hash)).isTrue();
    }

    @Test
    void repeatedBootstrapIsStrictlyIdempotent() throws Exception {
        runBootstrap(FIXTURE_LOGIN_INPUT);
        String firstCredentialState = credentialState();
        String firstFixtureState = fixtureVersionState();

        runBootstrap(FIXTURE_LOGIN_INPUT);

        assertCompleteFixture();
        assertThat(credentialState()).isEqualTo(firstCredentialState);
        assertThat(fixtureVersionState()).isEqualTo(firstFixtureState);
    }

    @Test
    void successfulLoginMetadataRemainsValidAcrossLocalRestart() throws Exception {
        runBootstrap(FIXTURE_LOGIN_INPUT);
        new JooqCredentialRepository(DSL.using(dataSource, SQLDialect.POSTGRES))
            .markLoginSucceeded(100L);
        OffsetDateTime lastLoginAt = jdbc.queryForObject("""
            SELECT last_login_at FROM iam_authentication_credential WHERE user_id = 100
            """, OffsetDateTime.class);
        Long rowVersion = jdbc.queryForObject("""
            SELECT row_version FROM iam_authentication_credential WHERE user_id = 100
            """, Long.class);

        runBootstrap(FIXTURE_LOGIN_INPUT);

        assertCompleteFixture();
        assertThat(jdbc.queryForObject("""
            SELECT last_login_at FROM iam_authentication_credential WHERE user_id = 100
            """, OffsetDateTime.class)).isEqualTo(lastLoginAt);
        assertThat(jdbc.queryForObject("""
            SELECT row_version FROM iam_authentication_credential WHERE user_id = 100
            """, Long.class)).isEqualTo(rowVersion).isEqualTo(2L);
    }

    @Test
    void reservedIdentifierCollisionFailsWithoutAttachingFixtureRowsToTheWrongTenant() {
        jdbc.update("""
            INSERT INTO iam_tenant(id, tenant_code, tenant_name, tenant_type, status)
            VALUES (1, 'real-platform', 'Real Platform', 'PLATFORM', 'ACTIVE')
            """);

        assertThatThrownBy(() -> runBootstrap(FIXTURE_LOGIN_INPUT))
            .hasStackTraceContaining("local fixture footprint is incomplete or modified");

        assertThat(count("iam_tenant")).isOne();
        assertThat(count("iam_department")).isZero();
        assertThat(count("iam_membership")).isZero();
        assertThat(count("iam_role_grant")).isZero();
    }

    @Test
    void reservedNaturalKeyCollisionFailsWithoutPartialFixtureWrites() {
        jdbc.update("""
            INSERT INTO iam_tenant(id, tenant_code, tenant_name, tenant_type, status)
            VALUES (2, 'platform', 'Real Platform', 'PLATFORM', 'ACTIVE')
            """);

        assertThatThrownBy(() -> runBootstrap(FIXTURE_LOGIN_INPUT))
            .hasStackTraceContaining("local fixture footprint is incomplete or modified");

        assertThat(count("iam_tenant")).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM iam_tenant WHERE id = 1", Long.class)).isZero();
        assertThat(count("iam_department")).isZero();
    }

    @Test
    void partialFixtureFailsInsteadOfBeingSilentlyRepaired() throws Exception {
        runBootstrap(FIXTURE_LOGIN_INPUT);
        jdbc.update("DELETE FROM iam_role_menu WHERE tenant_id = 1 AND role_id = 2000 AND menu_id = 6012");

        assertThatThrownBy(() -> runBootstrap(FIXTURE_LOGIN_INPUT))
            .hasStackTraceContaining("local fixture footprint is incomplete or modified");

        assertThat(count("iam_role_menu")).isEqualTo(7);
    }

    @Test
    void exactLegacyEightMenuFixtureUpgradesTransactionally() throws Exception {
        runBootstrap(FIXTURE_LOGIN_INPUT);
        downgradeToLegacy(false);

        runBootstrap(FIXTURE_LOGIN_INPUT);

        assertCompleteFixture();
    }

    @Test
    void exactLegacyFourteenButtonFixtureUpgradesTransactionally() throws Exception {
        runBootstrap(FIXTURE_LOGIN_INPUT);
        downgradeToLegacy(true);

        runBootstrap(FIXTURE_LOGIN_INPUT);

        assertCompleteFixture();
    }

    @Test
    void v9EightMenuFixtureMigratedThroughV14BootstrapsTransactionally() throws Exception {
        restoreExactLegacyFixtureAtV9(false);

        migrateToLatest();

        assertExpandedMigratedLegacyFixture(8);
        runBootstrap(FIXTURE_LOGIN_INPUT);
        assertCompleteFixture();
        assertMigrationHistoryPreserved();
    }

    @Test
    void v9FourteenButtonFixtureMigratedThroughV14BootstrapsTransactionally() throws Exception {
        restoreExactLegacyFixtureAtV9(true);

        migrateToLatest();

        assertExpandedMigratedLegacyFixture(22);
        runBootstrap(FIXTURE_LOGIN_INPUT);
        assertCompleteFixture();
        assertMigrationHistoryPreserved();
    }

    @Test
    void v14FixtureWithAdvancedDepartmentVersionMigratesToLatestAndBootstraps() throws Exception {
        restoreExactLegacyFixtureAtV9(false);
        flyway("14").migrate();
        jdbc.update("UPDATE iam_department SET row_version=2 WHERE tenant_id=1 AND id=10");

        migrateToLatest();
        runBootstrap(FIXTURE_LOGIN_INPUT);

        assertCompleteFixture();
        assertThat(jdbc.queryForObject(
            "SELECT row_version FROM iam_department WHERE tenant_id=1 AND id=10", Long.class))
            .isEqualTo(2L);
    }

    @Test
    void latestFixtureWithAdvancedDepartmentVersionBootstraps() throws Exception {
        restoreExactLegacyFixtureAtV9(false);
        migrateToLatest();
        jdbc.update("UPDATE iam_department SET row_version=2 WHERE tenant_id=1 AND id=10");

        runBootstrap(FIXTURE_LOGIN_INPUT);

        assertCompleteFixture();
        assertThat(jdbc.queryForObject(
            "SELECT row_version FROM iam_department WHERE tenant_id=1 AND id=10", Long.class))
            .isEqualTo(2L);
    }

    @Test
    void advancedDepartmentVersionDoesNotPermitFixtureFieldDrift() {
        restoreExactLegacyFixtureAtV9(false);
        migrateToLatest();
        jdbc.update("""
            UPDATE iam_department
               SET department_name='Modified Head Office', row_version=2
             WHERE tenant_id=1 AND id=10
            """);

        assertThatThrownBy(() -> runBootstrap(FIXTURE_LOGIN_INPUT))
            .hasStackTraceContaining("local fixture footprint is incomplete or modified");

        assertThat(jdbc.queryForObject(
            "SELECT department_name FROM iam_department WHERE tenant_id=1 AND id=10", String.class))
            .isEqualTo("Modified Head Office");
        assertThat(count("iam_role_grant")).isEqualTo(21);
    }

    @Test
    void partialV14MigratedLegacyFixtureStillFailsWithoutMutation() {
        restoreExactLegacyFixtureAtV9(false);
        migrateToLatest();
        jdbc.update("""
            DELETE FROM iam_role_grant grant_row
             USING iam_permission permission
             WHERE grant_row.permission_id=permission.id
               AND grant_row.tenant_id=1 AND grant_row.role_id=2000
               AND permission.permission_code='menu:create'
               AND grant_row.grant_key='migration-v14-menu-create'
            """);

        assertThatThrownBy(() -> runBootstrap(FIXTURE_LOGIN_INPUT))
            .hasStackTraceContaining("local fixture footprint is incomplete or modified");

        assertThat(count("iam_role_grant")).isEqualTo(20);
        assertThat(count("iam_grant_dimension")).isEqualTo(20);
        assertThat(count("iam_menu")).isEqualTo(8);
        assertThat(jdbc.queryForObject(
            "SELECT password_hash FROM iam_authentication_credential WHERE user_id=100", String.class))
            .isNull();
    }

    @Test
    void failedLegacyMenuUpgradeRollsBackToTheExactEightMenuState() throws Exception {
        runBootstrap(FIXTURE_LOGIN_INPUT);
        downgradeToLegacy(false);
        jdbc.execute("""
            CREATE FUNCTION fail_local_permission_button_insert()
            RETURNS trigger
            LANGUAGE plpgsql
            AS $$
            BEGIN
                IF NEW.id = 6020 THEN
                    RAISE EXCEPTION 'forced local permission button failure';
                END IF;
                RETURN NEW;
            END;
            $$
            """);
        jdbc.execute("""
            CREATE TRIGGER trg_fail_local_permission_button_insert
            BEFORE INSERT ON iam_menu
            FOR EACH ROW EXECUTE FUNCTION fail_local_permission_button_insert()
            """);

        assertThatThrownBy(() -> runBootstrap(FIXTURE_LOGIN_INPUT))
            .hasStackTraceContaining("forced local permission button failure");

        assertThat(count("iam_menu")).isEqualTo(8);
        assertThat(count("iam_role_menu")).isEqualTo(8);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM iam_menu
             WHERE tenant_id = 1 AND menu_type = 'BUTTON'
            """, Long.class)).isZero();
    }

    @Test
    void partialPermissionButtonFixtureFailsInsteadOfBeingSilentlyRepaired() throws Exception {
        runBootstrap(FIXTURE_LOGIN_INPUT);
        jdbc.update("DELETE FROM iam_menu WHERE tenant_id = 1 AND id = 6040");

        assertThatThrownBy(() -> runBootstrap(FIXTURE_LOGIN_INPUT))
            .hasStackTraceContaining("local fixture footprint is incomplete or modified");

        assertThat(count("iam_menu")).isEqualTo(28);
        assertThat(count("iam_role_menu")).isEqualTo(8);
    }

    @Test
    void duplicateFixtureAuthCodeFailsInsteadOfCreatingAnAmbiguousCatalog() throws Exception {
        runBootstrap(FIXTURE_LOGIN_INPUT);
        jdbc.update("""
            INSERT INTO iam_menu(
                id, tenant_id, parent_id, menu_type, menu_name, route_name,
                sort_order, auth_code, status, meta_json
            ) VALUES (
                7200, 1, NULL, 'BUTTON', 'Duplicate User View', 'DuplicateUserView',
                500, 'user:view', 'ACTIVE', '{"title":"system.user.permission.view"}'::jsonb
            )
            """);

        assertThatThrownBy(() -> runBootstrap(FIXTURE_LOGIN_INPUT))
            .hasStackTraceContaining("local fixture footprint is incomplete or modified");

        assertThat(count("iam_menu")).isEqualTo(30);
    }

    @Test
    void modifiedFixtureIdentityFailsInsteadOfGrantingTheWrongSubject() throws Exception {
        runBootstrap(FIXTURE_LOGIN_INPUT);
        jdbc.update("UPDATE iam_user SET idp_subject = 'different-subject' WHERE id = 100");

        assertThatThrownBy(() -> runBootstrap(FIXTURE_LOGIN_INPUT))
            .hasStackTraceContaining("local fixture footprint is incomplete or modified");

        assertThat(jdbc.queryForObject("SELECT idp_subject FROM iam_user WHERE id = 100", String.class))
            .isEqualTo("different-subject");
        assertThat(count("iam_role_grant")).isEqualTo(19);
    }

    @Test
    void changedConfiguredPasswordFailsInsteadOfSilentlyKeepingUnknownCredentials() throws Exception {
        runBootstrap(FIXTURE_LOGIN_INPUT);
        String originalCredentialState = credentialState();

        assertThatThrownBy(() -> runBootstrap("a-different-password"))
            .hasStackTraceContaining("does not match payment.bootstrap-password");

        assertThat(credentialState()).isEqualTo(originalCredentialState);
    }

    @Test
    void productPermissionExtensionsRemainUntouched() throws Exception {
        insertPermissionExtension();

        runBootstrap(FIXTURE_LOGIN_INPUT);

        assertCompleteFixture();
        assertThat(count("iam_permission")).isEqualTo(22);
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM iam_permission WHERE id = 9001 AND permission_code = 'payout:view'",
            Long.class)).isOne();
    }

    @Test
    void missingRequiredPermissionFailsWithoutAnyFixtureWrites() {
        jdbc.update("DELETE FROM iam_permission WHERE id = 3001");

        assertThatThrownBy(() -> runBootstrap(FIXTURE_LOGIN_INPUT))
            .hasStackTraceContaining("required permission catalog is incomplete or modified");

        assertThat(count("iam_permission")).isEqualTo(20);
        assertThat(count("iam_tenant")).isZero();
        assertThat(count("iam_user")).isZero();
        assertThat(count("iam_role_grant")).isZero();
    }

    @Test
    void unrelatedProductionRowsDoNotBlockTheLocalFixture() throws Exception {
        jdbc.update("""
            INSERT INTO iam_tenant(id, tenant_code, tenant_name, tenant_type, status)
            VALUES (2, 'merchant-two', 'Merchant Two', 'DIRECT_MERCHANT', 'ACTIVE')
            """);
        jdbc.update("""
            INSERT INTO iam_user(id, idp_issuer, idp_subject, display_name, status)
            VALUES (200, 'production-idp', 'real-user', 'Real User', 'ACTIVE')
            """);
        jdbc.update("""
            INSERT INTO iam_audit_event(
                id, tenant_id, target_type, target_ref, action_code,
                decision, reason_code, trace_id
            ) VALUES (nextval('iam_id_seq'), 2, 'TENANT', '2', 'CREATED',
                      'NOT_APPLICABLE', 'TEST', 'real-audit')
            """);
        jdbc.update("""
            INSERT INTO iam_permission_change_outbox(
                id, tenant_id, aggregate_type, aggregate_ref, event_type, payload,
                aggregate_version, schema_version, partition_key, trace_id
            ) VALUES (nextval('iam_id_seq'), 2, 'TENANT', '2', 'TenantChanged',
                      '{}'::jsonb, 1, 1, '2', 'real-outbox')
            """);

        runBootstrap(FIXTURE_LOGIN_INPUT);

        assertCompleteFixture();
        assertThat(count("iam_tenant")).isEqualTo(2);
        assertThat(count("iam_user")).isEqualTo(2);
        assertThat(count("iam_audit_event")).isOne();
        assertThat(count("iam_permission_change_outbox")).isOne();
    }

    @Test
    void adminCreatedLocalIdentityDataSurvivesRestartWithoutOwningTheFixture() throws Exception {
        runBootstrap(FIXTURE_LOGIN_INPUT);
        jdbc.update("""
            INSERT INTO iam_department(
                id, tenant_id, parent_id, department_code, department_name, status, remark
            ) VALUES (7001, 1, 10, 'local-engineering', 'Local Engineering', 'ACTIVE',
                      'Created after local bootstrap')
            """);
        jdbc.update("""
            INSERT INTO iam_user(id, idp_issuer, idp_subject, display_name, status)
            VALUES (700, 'local', 'developer', 'Local Developer', 'ACTIVE')
            """);
        jdbc.update("""
            INSERT INTO iam_membership(id, tenant_id, user_id, department_id, status)
            VALUES (7000, 1, 700, 7001, 'ACTIVE')
            """);
        jdbc.update("""
            INSERT INTO iam_authentication_credential(user_id, username, password_hash, status)
            VALUES (700, 'developer', NULL, 'ACTIVE')
            """);
        jdbc.update("""
            INSERT INTO iam_role(
                id, tenant_id, role_code, role_name, applicable_tenant_type,
                assignable, system_role, status
            ) VALUES (7100, 1, 'local-developer', 'Local Developer', 'PLATFORM', true, false, 'ACTIVE')
            """);
        jdbc.update("""
            INSERT INTO iam_membership_role(tenant_id, membership_id, role_id, assigned_by)
            VALUES (1, 7000, 7100, 1000)
            """);
        jdbc.update("""
            INSERT INTO iam_role_grant(
                id, tenant_id, role_id, permission_id, grant_key, status, created_by, updated_by
            ) VALUES (7300, 1, 7100, 3001, 'local-developer-view', 'ACTIVE', 1000, 1000)
            """);
        jdbc.update("""
            INSERT INTO iam_grant_dimension(id, grant_id, dimension_code, scope_mode)
            VALUES (7400, 7300, 'TENANT', 'TENANT_ALL')
            """);
        jdbc.update("""
            INSERT INTO iam_menu(
                id, tenant_id, parent_id, menu_type, menu_name, route_name, route_path,
                component_path, sort_order, status, meta_json
            ) VALUES (
                7200, 1, 6000, 'PAGE', 'Local Developer', 'LocalDeveloper',
                '/local-developer', '/dashboard/workspace/index', 500, 'ACTIVE',
                '{"title":"local.developer"}'::jsonb
            )
            """);
        jdbc.update("""
            INSERT INTO iam_role_menu(tenant_id, role_id, menu_id)
            VALUES (1, 7100, 6001), (1, 7100, 7200)
            """);

        runBootstrap(FIXTURE_LOGIN_INPUT);

        assertCompleteFixture();
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM iam_department WHERE id = 7001 AND parent_id = 10", Long.class))
            .isOne();
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM iam_user WHERE id = 700 AND idp_subject = 'developer'", Long.class))
            .isOne();
        assertThat(jdbc.queryForObject(
            """
            SELECT count(*) FROM iam_membership_role
             WHERE membership_id = 7000 AND role_id = 7100 AND assigned_by = 1000
            """,
            Long.class)).isOne();
        assertThat(jdbc.queryForObject(
            """
            SELECT count(*) FROM iam_role_grant
             WHERE id = 7300 AND role_id = 7100 AND created_by = 1000 AND updated_by = 1000
            """, Long.class))
            .isOne();
        assertThat(jdbc.queryForObject(
            """
            SELECT count(*) FROM iam_menu
             WHERE id = 7200 AND route_name = 'LocalDeveloper' AND parent_id = 6000
            """, Long.class))
            .isOne();
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM iam_role_menu
             WHERE tenant_id = 1 AND role_id = 7100 AND menu_id IN (6001,7200)
            """, Long.class)).isEqualTo(2);
        assertThat(count("iam_department")).isEqualTo(2);
        assertThat(count("iam_user")).isEqualTo(2);
        assertThat(count("iam_role")).isEqualTo(2);
        assertThat(count("iam_menu")).isEqualTo(30);
    }

    @Test
    void relationshipAttachedToTheFixtureMembershipStillFailsWithoutMutation() throws Exception {
        runBootstrap(FIXTURE_LOGIN_INPUT);
        jdbc.update("""
            INSERT INTO iam_role(
                id, tenant_id, role_code, role_name, applicable_tenant_type,
                assignable, system_role, status
            ) VALUES (7100, 1, 'attached-role', 'Attached Role', 'PLATFORM', true, false, 'ACTIVE')
            """);
        jdbc.update("""
            INSERT INTO iam_membership_role(tenant_id, membership_id, role_id, assigned_by)
            VALUES (1, 1000, 7100, 1000)
            """);

        assertThatThrownBy(() -> runBootstrap(FIXTURE_LOGIN_INPUT))
            .hasStackTraceContaining("local fixture footprint is incomplete or modified");

        assertThat(count("iam_membership_role")).isEqualTo(2);
        assertThat(credentialState()).isNotBlank();
    }

    @Test
    void failureAfterEarlierInsertsRollsBackEveryFixtureTable() {
        jdbc.execute("""
            CREATE FUNCTION fail_local_fixture_role_insert()
            RETURNS trigger
            LANGUAGE plpgsql
            AS $$
            BEGIN
                IF NEW.id = 2000 THEN
                    RAISE EXCEPTION 'forced local fixture failure';
                END IF;
                RETURN NEW;
            END;
            $$
            """);
        jdbc.execute("""
            CREATE TRIGGER trg_fail_local_fixture_role_insert
            BEFORE INSERT ON iam_role
            FOR EACH ROW EXECUTE FUNCTION fail_local_fixture_role_insert()
            """);

        assertThatThrownBy(() -> runBootstrap(FIXTURE_LOGIN_INPUT))
            .hasStackTraceContaining("forced local fixture failure");

        assertThat(count("iam_tenant")).isZero();
        assertThat(count("iam_department")).isZero();
        assertThat(count("iam_user")).isZero();
        assertThat(count("iam_membership")).isZero();
        assertThat(count("iam_authentication_credential")).isZero();
        assertThat(count("iam_role")).isZero();
        assertThat(count("iam_membership_role")).isZero();
        assertThat(count("iam_role_grant")).isZero();
        assertThat(count("iam_grant_dimension")).isZero();
        assertThat(count("iam_menu")).isZero();
        assertThat(count("iam_role_menu")).isZero();
        assertThat(count("iam_permission")).isEqualTo(21);
    }

    @Test
    void bootstrapComponentIsRegisteredOnlyForTheLocalProfile() {
        Profile profile = LocalIdentityFixtureBootstrap.class.getAnnotation(Profile.class);

        assertThat(profile).isNotNull();
        assertThat(profile.value()).containsExactly("local");
    }

    private void runBootstrap(String password) throws Exception {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(10);
        LocalIdentityFixtureBootstrap bootstrap = new LocalIdentityFixtureBootstrap(
            dataSource,
            jdbc,
            new DataSourceTransactionManager(dataSource),
            encoder,
            password);
        bootstrap.run(new DefaultApplicationArguments(new String[0]));
    }

    private void restoreExactLegacyFixtureAtV9(boolean includeButtons) {
        flyway(null).clean();
        flyway("7").migrate();
        for (String table : LEGACY_FIXTURE_TABLES) {
            jdbc.execute("CREATE TABLE local_v9_backup_" + table + " AS TABLE " + table);
        }

        flyway("9").migrate();

        for (String table : LEGACY_FIXTURE_TABLES) {
            jdbc.execute("INSERT INTO " + table + " SELECT * FROM local_v9_backup_" + table);
        }
        jdbc.update("UPDATE iam_menu SET row_version=0 WHERE tenant_id=1");
        for (String table : LEGACY_FIXTURE_TABLES) {
            jdbc.execute("DROP TABLE local_v9_backup_" + table);
        }
        if (includeButtons) {
            insertLegacyPermissionButtons();
        }
        assertThat(count("iam_permission")).isEqualTo(14);
        assertThat(count("iam_role_grant")).isEqualTo(14);
        assertThat(count("iam_grant_dimension")).isEqualTo(14);
        assertThat(count("iam_menu")).isEqualTo(includeButtons ? 22 : 8);
        assertThat(count("iam_role_menu")).isEqualTo(8);
    }

    private void insertLegacyPermissionButtons() {
        jdbc.update("""
            INSERT INTO iam_menu(
                id,tenant_id,parent_id,menu_type,menu_name,route_name,sort_order,auth_code,status,meta_json
            ) VALUES
              (6020,1,6001,'BUTTON','View Users','UserView',111,'user:view','ACTIVE',
               '{"title":"system.user.permission.view"}'::jsonb),
              (6021,1,6001,'BUTTON','Create User','UserCreate',112,'user:create','ACTIVE',
               '{"title":"system.user.permission.create"}'::jsonb),
              (6022,1,6001,'BUTTON','Update User','UserUpdate',113,'user:update','ACTIVE',
               '{"title":"system.user.permission.update"}'::jsonb),
              (6023,1,6001,'BUTTON','Delete User','UserDelete',114,'user:delete','ACTIVE',
               '{"title":"system.user.permission.delete"}'::jsonb),
              (6024,1,6001,'BUTTON','Disable User','UserDisable',115,'user:disable','ACTIVE',
               '{"title":"system.user.permission.disable"}'::jsonb),
              (6025,1,6001,'BUTTON','Assign User Roles','UserAssignRole',116,'user:assign-role','ACTIVE',
               '{"title":"system.user.permission.assignRole"}'::jsonb),
              (6026,1,6002,'BUTTON','View Roles','RoleView',121,'role:view','ACTIVE',
               '{"title":"system.role.permission.view"}'::jsonb),
              (6027,1,6002,'BUTTON','Create Role','RoleCreate',122,'role:create','ACTIVE',
               '{"title":"system.role.permission.create"}'::jsonb),
              (6028,1,6002,'BUTTON','Update Role','RoleUpdate',123,'role:update','ACTIVE',
               '{"title":"system.role.permission.update"}'::jsonb),
              (6029,1,6002,'BUTTON','Delete Role','RoleDelete',124,'role:delete','ACTIVE',
               '{"title":"system.role.permission.delete"}'::jsonb),
              (6030,1,6003,'BUTTON','View Menus','MenuView',131,'menu:view','ACTIVE',
               '{"title":"system.menu.permission.view"}'::jsonb),
              (6031,1,6003,'BUTTON','Manage Menus','MenuManage',132,'menu:manage','ACTIVE',
               '{"title":"system.menu.permission.manage"}'::jsonb),
              (6032,1,6004,'BUTTON','View Departments','DepartmentView',141,'department:view','ACTIVE',
               '{"title":"system.dept.permission.view"}'::jsonb),
              (6033,1,6004,'BUTTON','Manage Departments','DepartmentManage',142,'department:manage','ACTIVE',
               '{"title":"system.dept.permission.manage"}'::jsonb)
            """);
    }

    private void migrateToLatest() {
        flyway(null).migrate();
    }

    private Flyway flyway(String version) {
        var configuration = Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration")
            .cleanDisabled(false);
        if (version != null) {
            configuration.target(version);
        }
        return configuration.load();
    }

    private void assertExpandedMigratedLegacyFixture(int expectedMenuCount) {
        assertThat(count("iam_permission")).isEqualTo(21);
        assertThat(count("iam_role_grant")).isEqualTo(21);
        assertThat(count("iam_grant_dimension")).isEqualTo(21);
        assertThat(count("iam_menu")).isEqualTo(expectedMenuCount);
        assertThat(jdbc.queryForObject(
            "SELECT row_version FROM iam_role WHERE id=2000", Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
            "SELECT permission_version FROM iam_membership WHERE id=1000", Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM iam_permission
             WHERE permission_code IN ('menu:manage','department:manage') AND status='ACTIVE'
            """, Long.class)).isEqualTo(2);
    }

    private void assertMigrationHistoryPreserved() {
        assertThat(jdbc.queryForObject(
            "SELECT row_version FROM iam_role WHERE id=2000", Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
            "SELECT permission_version FROM iam_membership WHERE id=1000", Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM iam_audit_event
             WHERE tenant_id=1 AND target_type='ROLE_GRANTS' AND target_ref='2000'
               AND action_code='MIGRATE_GRANULAR_ADMIN_PERMISSIONS' AND trace_id='migration-v14'
            """, Long.class)).isOne();
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM iam_permission_change_outbox
             WHERE tenant_id=1 AND aggregate_type='MEMBERSHIP' AND aggregate_ref='1000'
               AND event_type='PERMISSION_VERSION_CHANGED' AND trace_id='migration-v14'
            """, Long.class)).isOne();
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM iam_audit_event
             WHERE tenant_id=1 AND target_type='ROLE_GRANTS' AND target_ref='2000'
               AND action_code='EXPAND_LEGACY_ADMIN_PERMISSIONS' AND trace_id='migration-v15'
            """, Long.class)).isOne();
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM iam_permission_change_outbox
             WHERE tenant_id=1 AND aggregate_type='MEMBERSHIP' AND aggregate_ref='1000'
               AND event_type='PERMISSION_VERSION_CHANGED' AND trace_id='migration-v15'
            """, Long.class)).isOne();
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM iam_role_grant
             WHERE tenant_id=1 AND role_id=2000 AND grant_key LIKE 'migration-v14-%'
            """, Long.class)).isZero();
    }

    private void assertCompleteFixture() {
        assertThat(jdbc.queryForObject("""
            SELECT count(*)
              FROM iam_tenant tenant
              JOIN iam_department department ON department.tenant_id = tenant.id
              JOIN iam_membership membership
                ON membership.tenant_id = tenant.id AND membership.department_id = department.id
              JOIN iam_user user_account ON user_account.id = membership.user_id
              JOIN iam_authentication_credential credential ON credential.user_id = user_account.id
              JOIN iam_role role ON role.tenant_id = tenant.id
              JOIN iam_membership_role membership_role
                ON membership_role.tenant_id = tenant.id
               AND membership_role.membership_id = membership.id
               AND membership_role.role_id = role.id
             WHERE tenant.id = 1 AND tenant.tenant_code = 'platform'
               AND department.id = 10 AND department.department_code = 'head-office'
               AND membership.id = 1000 AND membership.status = 'ACTIVE'
               AND user_account.id = 100 AND user_account.idp_issuer = 'local'
               AND user_account.idp_subject = 'admin' AND user_account.status = 'ACTIVE'
               AND credential.username = 'admin' AND credential.password_hash IS NOT NULL
               AND credential.status = 'ACTIVE'
               AND role.id = 2000 AND role.role_code = 'platform-admin'
               AND role.system_role AND NOT role.assignable AND role.status = 'ACTIVE'
            """, Long.class)).isOne();
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM iam_role_grant WHERE tenant_id = 1 AND role_id = 2000",
            Long.class)).isEqualTo(19);
        assertThat(jdbc.queryForObject("""
            SELECT count(*)
              FROM iam_grant_dimension dimension_row
              JOIN iam_role_grant grant_row ON grant_row.id = dimension_row.grant_id
             WHERE grant_row.tenant_id = 1 AND grant_row.role_id = 2000
            """, Long.class)).isEqualTo(19);
        assertThat(jdbc.queryForObject("""
            SELECT count(*)
              FROM iam_grant_target target
              JOIN iam_grant_dimension dimension_row ON dimension_row.id = target.dimension_id
              JOIN iam_role_grant grant_row ON grant_row.id = dimension_row.grant_id
             WHERE grant_row.tenant_id = 1 AND grant_row.role_id = 2000
            """, Long.class)).isZero();
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM iam_menu
             WHERE tenant_id = 1
               AND id IN (6000, 6001, 6002, 6003, 6004, 6010, 6011, 6012)
            """, Long.class)).isEqualTo(8);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM iam_role_menu
             WHERE tenant_id = 1 AND role_id = 2000
               AND menu_id IN (6000, 6001, 6002, 6003, 6004, 6010, 6011, 6012)
            """, Long.class)).isEqualTo(8);
        assertThat(jdbc.queryForObject("""
            SELECT count(*)
              FROM iam_menu menu
              JOIN iam_permission permission ON permission.permission_code = menu.auth_code
             WHERE menu.tenant_id = 1 AND menu.menu_type = 'BUTTON'
               AND menu.status = 'ACTIVE' AND permission.status = 'ACTIVE'
               AND menu.route_path IS NULL AND menu.component_path IS NULL
               AND menu.redirect_path IS NULL
            """, Long.class)).isEqualTo(19);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM iam_menu
             WHERE tenant_id = 1 AND id IN (6031,6033) AND menu_type = 'BUTTON'
               AND status = 'DISABLED' AND meta_json ->> 'hideInMenu' = 'true'
            """, Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM iam_menu
             WHERE tenant_id = 1 AND menu_type = 'BUTTON'
            """, Long.class)).isEqualTo(21);
        assertThat(jdbc.queryForObject("""
            SELECT count(*)
              FROM iam_role_menu role_menu
              JOIN iam_menu menu
                ON menu.id = role_menu.menu_id AND menu.tenant_id = role_menu.tenant_id
             WHERE role_menu.tenant_id = 1
               AND role_menu.role_id = 2000
               AND menu.menu_type = 'BUTTON'
            """, Long.class)).isZero();
    }

    private void downgradeToLegacy(boolean includeButtons) {
        jdbc.update("DELETE FROM iam_role_grant WHERE tenant_id=1 AND role_id=2000 AND permission_id BETWEEN 3015 AND 3021");
        jdbc.update("""
            INSERT INTO iam_role_grant(
                id,tenant_id,role_id,permission_id,grant_key,status,created_by,updated_by
            ) VALUES
              (4012,1,2000,3012,'menu-manage','ACTIVE',1000,1000),
              (4014,1,2000,3014,'department-manage','ACTIVE',1000,1000)
            """);
        jdbc.update("""
            INSERT INTO iam_grant_dimension(id,grant_id,dimension_code,scope_mode)
            VALUES (5012,4012,'TENANT','TENANT_ALL'),(5014,4014,'TENANT','TENANT_ALL')
            """);
        if (!includeButtons) {
            jdbc.update("DELETE FROM iam_menu WHERE tenant_id=1 AND menu_type='BUTTON'");
            return;
        }
        jdbc.update("DELETE FROM iam_menu WHERE tenant_id=1 AND id BETWEEN 6034 AND 6040");
        jdbc.update("""
            UPDATE iam_menu SET status='ACTIVE', meta_json='{"title":"system.menu.permission.manage"}'::jsonb,
                   row_version=0 WHERE tenant_id=1 AND id=6031
            """);
        jdbc.update("""
            UPDATE iam_menu SET status='ACTIVE', meta_json='{"title":"system.dept.permission.manage"}'::jsonb,
                   row_version=0 WHERE tenant_id=1 AND id=6033
            """);
    }

    private String credentialState() {
        return jdbc.queryForObject("""
            SELECT password_hash || '|' || row_version || '|' || updated_at::text
              FROM iam_authentication_credential
             WHERE user_id = 100
            """, String.class);
    }

    private String fixtureVersionState() {
        return jdbc.queryForObject("""
            SELECT string_agg(state, ',' ORDER BY state)
              FROM (
                    SELECT 'tenant:' || row_version AS state FROM iam_tenant WHERE id = 1
                    UNION ALL SELECT 'department:' || row_version FROM iam_department WHERE id = 10
                    UNION ALL SELECT 'user:' || row_version FROM iam_user WHERE id = 100
                    UNION ALL SELECT 'membership:' || row_version FROM iam_membership WHERE id = 1000
                    UNION ALL SELECT 'role:' || row_version FROM iam_role WHERE id = 2000
                    UNION ALL SELECT 'grant:' || id || ':' || row_version
                              FROM iam_role_grant WHERE role_id = 2000
                    UNION ALL SELECT 'menu:' || id || ':' || row_version
                              FROM iam_menu WHERE tenant_id = 1
                   ) fixture_versions
            """, String.class);
    }

    private long count(String table) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
        return count == null ? 0 : count;
    }

    private void insertPermissionExtension() {
        jdbc.update("""
            INSERT INTO iam_permission(
                id, permission_code, resource_code, action_code, risk_level,
                required_dimensions, requires_step_up, requires_approval, status,
                description, cross_tenant_mode
            ) VALUES (9001, 'payout:view', 'payout', 'view', 'NORMAL',
                      ARRAY['TENANT']::varchar(32)[], false, false, 'ACTIVE',
                      'Product extension', 'SAME_TENANT_ONLY')
            """);
    }
}
