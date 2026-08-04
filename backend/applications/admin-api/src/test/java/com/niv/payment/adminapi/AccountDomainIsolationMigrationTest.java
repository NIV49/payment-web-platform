package com.niv.payment.adminapi;

import com.niv.payment.permission.domain.AdministrationActor;
import com.niv.payment.permission.persistence.repository.JooqRoleGrantAdministrationRepository;
import com.niv.payment.permission.service.RoleGrantAdministrationService;
import com.niv.payment.permission.service.RoleGrantChangeCommand;
import org.flywaydb.core.Flyway;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class AccountDomainIsolationMigrationTest {
    private static final String BLOCK_MESSAGE =
        "IAM-001 migration blocked: every user must resolve to exactly one account domain";

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
    void freshSchemaCreatesRequiredDomainColumnsAndConstraints() throws Exception {
        flyway(null).migrate();

        assertThat(singleLong("""
            SELECT count(*) FROM information_schema.columns
             WHERE table_schema='public' AND column_name='account_domain'
               AND table_name IN ('iam_tenant','iam_user','iam_membership','iam_authentication_credential')
               AND is_nullable='NO'
            """)).isEqualTo(4L);
        assertThat(singleLong("""
            SELECT count(*) FROM pg_constraint
             WHERE conname IN ('fk_iam_membership_tenant_domain',
                               'fk_iam_membership_user_domain',
                               'fk_iam_authentication_user_domain')
            """)).isEqualTo(3L);
        assertThat(singleLong("""
            SELECT count(*) FROM iam_permission
             WHERE (id=3022 AND permission_code='backoffice:platform-access')
                OR (id=3023 AND permission_code='backoffice:merchant-access')
                OR (id=3024 AND permission_code='backoffice:agent-access')
            """)).isEqualTo(3L);
    }

    @Test
    void preflightEnumeratesEveryBlockingUserInStableOrder() throws Exception {
        flyway("17").migrate();
        execute("""
            INSERT INTO iam_tenant(id,tenant_code,tenant_name,tenant_type,status) VALUES
              (131,'platform-131','Platform 131','PLATFORM','ACTIVE'),
              (132,'agent-132','Agent 132','AGENT','ACTIVE');
            INSERT INTO iam_user(id,idp_issuer,idp_subject,display_name,status) VALUES
              (133,'migration-test','unowned-133','Unowned User','ACTIVE'),
              (134,'migration-test','cross-domain-134','Cross Domain User','ACTIVE');
            INSERT INTO iam_membership(id,tenant_id,user_id,status) VALUES
              (135,131,134,'ACTIVE'),
              (136,132,134,'ACTIVE');
            """);

        String preflight = Files.readString(Path.of("..", "..", "scripts",
            "iam001-account-domain-preflight.sql"));
        var observed = new ArrayList<String>();
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(preflight)) {
            while (rows.next()) {
                observed.add(rows.getLong("user_id") + ":" + rows.getString("issue_code")
                    + ":" + rows.getString("observed_account_domains"));
            }
        }

        assertThat(observed).containsExactly(
            "133:NO_MEMBERSHIP:",
            "134:CROSS_ACCOUNT_DOMAIN:AGENT,PLATFORM");
    }

    @Test
    void singleDomainHistoryBackfillsAllRows() throws Exception {
        flyway("17").migrate();
        execute("""
            INSERT INTO iam_tenant(id,tenant_code,tenant_name,tenant_type,status)
            VALUES(91,'merchant-91','Merchant 91','DIRECT_MERCHANT','ACTIVE');
            INSERT INTO iam_user(id,idp_issuer,idp_subject,display_name,status)
            VALUES(92,'migration-test','merchant-user-92','Merchant User','ACTIVE');
            INSERT INTO iam_membership(id,tenant_id,user_id,status)
            VALUES(93,91,92,'ACTIVE');
            INSERT INTO iam_authentication_credential(user_id,username,password_hash,status)
            VALUES(92,'merchant-user-92',NULL,'ACTIVE');
            """);

        flyway(null).migrate();

        assertThat(singleLong("SELECT count(*) FROM iam_tenant WHERE id=91 AND account_domain='MERCHANT'"))
            .isOne();
        assertThat(singleLong("SELECT count(*) FROM iam_user WHERE id=92 AND account_domain='MERCHANT'"))
            .isOne();
        assertThat(singleLong("SELECT count(*) FROM iam_membership WHERE id=93 AND account_domain='MERCHANT'"))
            .isOne();
        assertThat(singleLong("""
            SELECT count(*) FROM iam_authentication_credential
             WHERE user_id=92 AND account_domain='MERCHANT'
            """)).isOne();
    }

    @Test
    void portalAccessMigrationBackfillsOneCanonicalGrantForEveryLiveHistoricalRole() throws Exception {
        flyway("17").migrate();
        execute("""
            INSERT INTO iam_tenant(id,tenant_code,tenant_name,tenant_type,status) VALUES
              (141,'platform-141','Platform 141','PLATFORM','ACTIVE'),
              (142,'merchant-142','Merchant 142','DIRECT_MERCHANT','ACTIVE'),
              (143,'agent-143','Agent 143','AGENT','ACTIVE');
            INSERT INTO iam_role(id,tenant_id,role_code,role_name,applicable_tenant_type,
                                 assignable,system_role,status,deleted_at) VALUES
              (151,141,'platform-role','Platform Role','PLATFORM',true,false,'ACTIVE',NULL),
              (152,142,'merchant-role','Merchant Role','DIRECT_MERCHANT',true,false,'ACTIVE',NULL),
              (153,143,'agent-role','Agent Role','AGENT',true,false,'DISABLED',NULL),
              (154,141,'deleted-role','Deleted Role','PLATFORM',true,false,'DISABLED',CURRENT_TIMESTAMP);
            """);

        flyway(null).migrate();

        assertThat(currentSuccessfulVersion()).isEqualTo("20");
        assertThat(singleLong("""
            SELECT count(*)
              FROM iam_role role_row
              JOIN iam_tenant tenant ON tenant.id=role_row.tenant_id
              JOIN iam_role_grant grant_row
                ON grant_row.tenant_id=role_row.tenant_id AND grant_row.role_id=role_row.id
              JOIN iam_permission permission ON permission.id=grant_row.permission_id
              JOIN iam_grant_dimension dimension ON dimension.grant_id=grant_row.id
             WHERE role_row.id IN (151,152,153)
               AND grant_row.grant_key='system-backoffice-access'
               AND grant_row.status='ACTIVE'
               AND grant_row.valid_from IS NULL AND grant_row.valid_until IS NULL
               AND dimension.dimension_code='TENANT' AND dimension.scope_mode='TENANT_ALL'
               AND permission.permission_code=CASE tenant.account_domain
                   WHEN 'PLATFORM' THEN 'backoffice:platform-access'
                   WHEN 'MERCHANT' THEN 'backoffice:merchant-access'
                   WHEN 'AGENT' THEN 'backoffice:agent-access'
               END
               AND NOT EXISTS (
                   SELECT 1 FROM iam_grant_dimension extra
                    WHERE extra.grant_id=grant_row.id AND extra.id<>dimension.id)
               AND NOT EXISTS (
                   SELECT 1 FROM iam_grant_target target
                    WHERE target.dimension_id=dimension.id)
            """)).isEqualTo(3L);
        assertThat(singleLong("""
            SELECT count(*) FROM iam_role_grant WHERE role_id=154
            """)).isZero();
    }

    @Test
    void legalV17ReservedKeyConflictIsRenamedAndRemainsRoundTripEditable() throws Exception {
        flyway("17").migrate();
        execute("""
            INSERT INTO iam_tenant(id,tenant_code,tenant_name,tenant_type,status)
            VALUES(171,'platform-171','Platform 171','PLATFORM','ACTIVE');
            INSERT INTO iam_user(id,idp_issuer,idp_subject,display_name,status)
            VALUES(172,'migration-test','platform-user-172','Platform User','ACTIVE');
            INSERT INTO iam_membership(id,tenant_id,user_id,status)
            VALUES(173,171,172,'ACTIVE');
            INSERT INTO iam_authentication_credential(user_id,username,password_hash,status)
            VALUES(172,'platform-user-172',
              '$2a$12$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy','ACTIVE');
            INSERT INTO iam_role(id,tenant_id,role_code,role_name,applicable_tenant_type,
                                 assignable,system_role,status,deleted_at) VALUES
              (174,171,'system-174','System 174','PLATFORM',false,true,'ACTIVE',NULL),
              (175,171,'target-175','Target 175','PLATFORM',true,false,'ACTIVE',NULL);
            INSERT INTO iam_membership_role(tenant_id,membership_id,role_id,assigned_by) VALUES
              (171,173,174,173),
              (171,173,175,173);
            INSERT INTO iam_role_grant(id,tenant_id,role_id,permission_id,grant_key,status) VALUES
              (176,171,174,(SELECT id FROM iam_permission WHERE permission_code='role:view'),
               'migration-role-view','ACTIVE'),
              (177,171,174,(SELECT id FROM iam_permission WHERE permission_code='role:grant-update'),
               'migration-role-grant-update','ACTIVE'),
              (178,171,175,(SELECT id FROM iam_permission WHERE permission_code='user:view'),
               'system-backoffice-access','ACTIVE');
            INSERT INTO iam_grant_dimension(id,grant_id,dimension_code,scope_mode) VALUES
              (179,176,'TENANT','TENANT_ALL'),
              (180,177,'TENANT','TENANT_ALL'),
              (181,178,'TENANT','TENANT_ALL');
            """);

        flyway(null).migrate();

        assertThat(currentSuccessfulVersion()).isEqualTo("20");
        assertThat(singleLong("""
            SELECT count(*) FROM iam_role_grant
             WHERE tenant_id=171 AND role_id=175 AND status='ACTIVE'
               AND grant_key='system-backoffice-access'
            """)).isOne();
        assertThat(singleLong("""
            SELECT count(*) FROM iam_role_grant grant_row
              JOIN iam_permission permission ON permission.id=grant_row.permission_id
             WHERE grant_row.id=178 AND grant_row.status='ACTIVE'
               AND grant_row.grant_key='legacy-backoffice-access-178'
               AND permission.permission_code='user:view'
            """)).isOne();
        assertThat(singleLong("""
            SELECT count(*) FROM iam_grant_dimension
             WHERE grant_id=178 AND dimension_code='TENANT' AND scope_mode='TENANT_ALL'
            """)).isOne();
        assertThat(singleLong("""
            SELECT count(*) FROM iam_audit_event
             WHERE tenant_id=171 AND target_type='ROLE_GRANT' AND target_ref='178'
               AND action_code='MIGRATE_RESERVED_GRANT_KEY'
               AND before_value->>'grantKey'='system-backoffice-access'
               AND after_value->>'grantKey'='legacy-backoffice-access-178'
            """)).isOne();
        assertThat(singleLong("SELECT row_version FROM iam_role WHERE tenant_id=171 AND id=175"))
            .isEqualTo(2L);
        assertThat(singleLong("SELECT permission_version FROM iam_membership WHERE tenant_id=171 AND id=173"))
            .isEqualTo(2L);
        assertThat(singleLong("""
            SELECT count(*) FROM iam_permission_change_outbox
             WHERE tenant_id=171 AND aggregate_type='MEMBERSHIP' AND aggregate_ref='173'
               AND payload->>'reason'='V20_RESERVED_GRANT_KEY_CONVERGENCE'
            """)).isOne();

        try (Connection database = connection()) {
            var repository = new JooqRoleGrantAdministrationRepository(
                DSL.using(database, SQLDialect.POSTGRES), () -> "migration-v20-round-trip");
            var service = new RoleGrantAdministrationService(repository, repository, true);
            var actor = new AdministrationActor(173L, 172L, 2L, 0L);
            var current = service.find(171L, actor, 175L);
            assertThat(current.editable()).isTrue();
            assertThat(current.grants()).singleElement().satisfies(grant -> {
                assertThat(grant.grantKey()).isEqualTo("legacy-backoffice-access-178");
                assertThat(grant.permission().value()).isEqualTo("user:view");
            });

            var replaced = service.replace(new RoleGrantChangeCommand(
                171L, 175L, current.roleVersion(), actor,
                "V20 migration round-trip", current.grants()));
            assertThat(replaced.roleVersion()).isEqualTo(3L);
            assertThat(replaced.grants()).containsExactlyElementsOf(current.grants());
            var refreshedActor = new AdministrationActor(173L, 172L, 3L, 0L);
            assertThat(service.find(171L, refreshedActor, 175L).grants())
                .containsExactlyElementsOf(current.grants());
        }
        assertThat(singleLong("""
            SELECT count(*) FROM iam_audit_event
             WHERE tenant_id=171 AND target_type='ROLE_GRANTS' AND target_ref='175'
               AND action_code='REPLACE' AND trace_id='migration-v20-round-trip'
            """)).isOne();
    }

    @Test
    void abnormalPortalGrantInventoryBlocksV20Atomically() throws Exception {
        flyway("19").migrate();
        execute("""
            INSERT INTO iam_tenant(id,tenant_code,tenant_name,tenant_type,status,account_domain)
            VALUES(191,'platform-191','Platform 191','PLATFORM','ACTIVE','PLATFORM');
            INSERT INTO iam_role(id,tenant_id,role_code,role_name,applicable_tenant_type,
                                 assignable,system_role,status,deleted_at)
            VALUES(192,191,'target-192','Target 192','PLATFORM',true,false,'ACTIVE',NULL);
            INSERT INTO iam_role_grant(id,tenant_id,role_id,permission_id,grant_key,status) VALUES
              (193,191,192,3022,'system-backoffice-access','ACTIVE'),
              (194,191,192,3023,'unexpected-merchant-portal','ACTIVE');
            INSERT INTO iam_grant_dimension(id,grant_id,dimension_code,scope_mode) VALUES
              (195,193,'TENANT','TENANT_ALL'),
              (196,194,'TENANT','TENANT_ALL');
            """);

        assertThatThrownBy(() -> flyway(null).migrate())
            .hasStackTraceContaining("IAM-001 V20 blocked: malformed protected backoffice access inventory");

        assertThat(currentSuccessfulVersion()).isEqualTo("19");
        assertThat(singleLong("SELECT count(*) FROM iam_role_grant WHERE role_id=192"))
            .isEqualTo(2L);
        assertThat(singleLong("""
            SELECT count(*) FROM iam_audit_event
             WHERE tenant_id=191 AND trace_id='migration-v20'
            """)).isZero();
    }

    @Test
    void occupiedDeterministicLegacyKeyBlocksV20Atomically() throws Exception {
        flyway("17").migrate();
        execute("""
            INSERT INTO iam_tenant(id,tenant_code,tenant_name,tenant_type,status)
            VALUES(201,'platform-201','Platform 201','PLATFORM','ACTIVE');
            INSERT INTO iam_role(id,tenant_id,role_code,role_name,applicable_tenant_type,
                                 assignable,system_role,status,deleted_at)
            VALUES(202,201,'target-202','Target 202','PLATFORM',true,false,'ACTIVE',NULL);
            INSERT INTO iam_role_grant(id,tenant_id,role_id,permission_id,grant_key,status) VALUES
              (203,201,202,(SELECT id FROM iam_permission WHERE permission_code='user:view'),
               'system-backoffice-access','ACTIVE'),
              (204,201,202,(SELECT id FROM iam_permission WHERE permission_code='role:view'),
               'legacy-backoffice-access-203','ACTIVE');
            INSERT INTO iam_grant_dimension(id,grant_id,dimension_code,scope_mode) VALUES
              (205,203,'TENANT','TENANT_ALL'),
              (206,204,'TENANT','TENANT_ALL');
            """);

        assertThatThrownBy(() -> flyway(null).migrate())
            .hasStackTraceContaining("IAM-001 V20 blocked: deterministic legacy grant key already exists");

        assertThat(currentSuccessfulVersion()).isEqualTo("19");
        assertThat(singleLong("""
            SELECT count(*) FROM iam_role_grant
             WHERE tenant_id=201 AND role_id=202
               AND ((id=203 AND grant_key='system-backoffice-access')
                 OR (id=204 AND grant_key='legacy-backoffice-access-203'))
            """)).isEqualTo(2L);
        assertThat(singleLong("SELECT row_version FROM iam_role WHERE tenant_id=201 AND id=202"))
            .isEqualTo(1L);
        assertThat(singleLong("""
            SELECT count(*) FROM iam_audit_event
             WHERE tenant_id=201 AND trace_id='migration-v20'
            """)).isZero();
        assertThat(singleLong("""
            SELECT count(*) FROM iam_permission_change_outbox
             WHERE tenant_id=201 AND payload->>'reason'='V20_RESERVED_GRANT_KEY_CONVERGENCE'
            """)).isZero();
    }

    @Test
    void crossDomainMembershipHistoryBlocksAtomically() throws Exception {
        flyway("17").migrate();
        execute("""
            INSERT INTO iam_tenant(id,tenant_code,tenant_name,tenant_type,status) VALUES
              (101,'platform-101','Platform 101','PLATFORM','ACTIVE'),
              (102,'agent-102','Agent 102','AGENT','ACTIVE');
            INSERT INTO iam_user(id,idp_issuer,idp_subject,display_name,status)
            VALUES(103,'migration-test','cross-domain-103','Cross Domain User','ACTIVE');
            INSERT INTO iam_membership(id,tenant_id,user_id,status) VALUES
              (104,101,103,'ACTIVE'),
              (105,102,103,'ACTIVE');
            """);

        assertThatThrownBy(() -> flyway(null).migrate())
            .hasStackTraceContaining(BLOCK_MESSAGE);

        assertThat(currentSuccessfulVersion()).isEqualTo("17");
        assertThat(accountDomainColumnCount()).isZero();
    }

    @Test
    void userWithoutMembershipBlocksAtomically() throws Exception {
        flyway("17").migrate();
        execute("""
            INSERT INTO iam_user(id,idp_issuer,idp_subject,display_name,status)
            VALUES(111,'migration-test','unowned-111','Unowned User','ACTIVE');
            """);

        assertThatThrownBy(() -> flyway(null).migrate())
            .hasStackTraceContaining(BLOCK_MESSAGE);

        assertThat(currentSuccessfulVersion()).isEqualTo("17");
        assertThat(accountDomainColumnCount()).isZero();
    }

    @Test
    void postMigrationConstraintsRejectDomainMismatch() throws Exception {
        flyway(null).migrate();
        execute("""
            INSERT INTO iam_tenant(id,tenant_code,tenant_name,tenant_type,status,account_domain)
            VALUES(121,'platform-121','Platform 121','PLATFORM','ACTIVE','PLATFORM');
            INSERT INTO iam_user(id,idp_issuer,idp_subject,display_name,status,account_domain)
            VALUES(122,'migration-test','agent-122','Agent User','ACTIVE','AGENT');
            """);

        assertThatThrownBy(() -> execute("""
            INSERT INTO iam_membership(id,tenant_id,user_id,status,account_domain)
            VALUES(123,121,122,'ACTIVE','PLATFORM')
            """))
            .hasStackTraceContaining("fk_iam_membership_user_domain");
        assertThatThrownBy(() -> execute("""
            INSERT INTO iam_tenant(id,tenant_code,tenant_name,tenant_type,status,account_domain)
            VALUES(124,'agent-124','Agent 124','AGENT','ACTIVE','MERCHANT')
            """))
            .hasStackTraceContaining("ck_iam_tenant_type_account_domain");
    }

    private static long accountDomainColumnCount() throws Exception {
        return singleLong("""
            SELECT count(*) FROM information_schema.columns
             WHERE table_schema='public' AND column_name='account_domain'
               AND table_name IN ('iam_tenant','iam_user','iam_membership','iam_authentication_credential')
            """);
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
