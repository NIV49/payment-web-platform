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
class ProductionIdentityFoundationMigrationTest {
    private static final String GLOBAL_USERNAME_GUARD =
        "IAM-002 migration blocked: global username uniqueness must remain during expand";

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
    void freshSchemaCreatesProductionIdentityFoundationWithoutContractingUsername() throws Exception {
        flyway(null).migrate();

        assertThat(currentSuccessfulVersion()).isEqualTo("23");
        assertThat(singleLong("""
            SELECT count(*) FROM information_schema.columns
             WHERE table_schema='public' AND table_name='iam_user'
               AND column_name IN ('identity_version','idp_provisioning_status')
               AND is_nullable='NO'
            """)).isEqualTo(2L);
        assertThat(singleLong("""
            SELECT count(*) FROM pg_constraint
             WHERE conrelid='iam_authentication_credential'::regclass
               AND contype='u'
               AND conname IN ('uk_iam_authentication_username',
                               'uk_iam_authentication_domain_username')
            """)).isEqualTo(2L);
        assertThat(singleLong("""
            SELECT count(*) FROM pg_constraint
             WHERE conrelid='iam_user'::regclass
               AND contype='u' AND conname='uk_iam_user_idp_identity'
            """)).isOne();
        assertThat(singleLong("""
            SELECT count(*) FROM information_schema.tables
             WHERE table_schema='public'
               AND table_name IN ('iam_tenant_entry_host',
                                  'iam_identity_lifecycle_outbox',
                                  'iam_identity_lifecycle_relay_state')
            """)).isEqualTo(3L);
        assertThat(singleLong("""
            SELECT count(*) FROM information_schema.columns
             WHERE table_schema='public' AND table_name='iam_identity_lifecycle_outbox'
               AND column_name IN ('payload','email','token','password','secret','recovery_code')
            """)).isZero();
    }

    @Test
    void v20UpgradeBackfillsProvisioningStateAndPreservesDomainUsernameRollout() throws Exception {
        flyway("20").migrate();
        execute("""
            INSERT INTO iam_tenant(id,tenant_code,tenant_name,tenant_type,status,account_domain) VALUES
              (21001,'merchant-21001','Merchant 21001','DIRECT_MERCHANT','ACTIVE','MERCHANT'),
              (21002,'agent-21002','Agent 21002','AGENT','ACTIVE','AGENT');
            INSERT INTO iam_user(id,idp_issuer,idp_subject,display_name,status,account_domain) VALUES
              (21003,'local','legacy-local','Legacy Local','ACTIVE','MERCHANT'),
              (21004,'https://agent-id.example.test/realms/agent','external-21004',
               'External Agent','ACTIVE','AGENT');
            INSERT INTO iam_authentication_credential(
                user_id,username,password_hash,status,account_domain
            ) VALUES
              (21003,'legacy-local',NULL,'DISABLED','MERCHANT'),
              (21004,'external-agent',NULL,'DISABLED','AGENT');
            """);

        flyway(null).migrate();

        assertThat(singleString("""
            SELECT idp_provisioning_status FROM iam_user WHERE id=21003
            """)).isEqualTo("LOCAL_ONLY");
        assertThat(singleString("""
            SELECT idp_provisioning_status FROM iam_user WHERE id=21004
            """)).isEqualTo("PROVISIONED");
        assertThat(singleLong("SELECT identity_version FROM iam_user WHERE id=21004"))
            .isZero();

        execute("ALTER TABLE iam_authentication_credential "
            + "DROP CONSTRAINT uk_iam_authentication_username");
        execute("""
            INSERT INTO iam_user(
                id,idp_issuer,idp_subject,display_name,status,account_domain,
                idp_provisioning_status
            ) VALUES
              (21005,'https://merchant-id.example.test/realms/merchant','merchant-21005',
               'Merchant Same Login','ACTIVE','MERCHANT','PROVISIONED'),
              (21006,'https://agent-id.example.test/realms/agent','agent-21006',
               'Agent Same Login','ACTIVE','AGENT','PROVISIONED'),
              (21007,'https://merchant-id.example.test/realms/merchant','merchant-21007',
               'Merchant Duplicate Login','ACTIVE','MERCHANT','PROVISIONED');
            INSERT INTO iam_authentication_credential(
                user_id,username,password_hash,status,account_domain
            ) VALUES
              (21005,'shared-login',NULL,'DISABLED','MERCHANT'),
              (21006,'shared-login',NULL,'DISABLED','AGENT');
            """);

        assertThatThrownBy(() -> execute("""
            INSERT INTO iam_authentication_credential(
                user_id,username,password_hash,status,account_domain
            ) VALUES(21007,'shared-login',NULL,'DISABLED','MERCHANT')
            """))
            .hasStackTraceContaining("uk_iam_authentication_domain_username");
    }

    @Test
    void hostRegistryAndLifecycleOutboxFailClosedAcrossDomains() throws Exception {
        flyway(null).migrate();
        execute("""
            INSERT INTO iam_tenant(id,tenant_code,tenant_name,tenant_type,status,account_domain) VALUES
              (22001,'merchant-22001','Merchant 22001','DIRECT_MERCHANT','ACTIVE','MERCHANT'),
              (22002,'agent-22002','Agent 22002','AGENT','ACTIVE','AGENT');
            INSERT INTO iam_user(
                id,idp_issuer,idp_subject,display_name,status,account_domain,
                idp_provisioning_status
            ) VALUES(22003,'https://merchant-id.example.test/realms/merchant','merchant-22003',
                     'Merchant User','ACTIVE','MERCHANT','PROVISIONED');
            INSERT INTO iam_tenant_entry_host(entry_host,account_domain,tenant_id,status)
            VALUES('merchant-22001.admin.example.test','MERCHANT',22001,'ACTIVE');
            INSERT INTO iam_identity_lifecycle_outbox(
                user_id,tenant_id,realm,operation_type,idempotency_key
            ) VALUES(
                22003,22001,'MERCHANT','MFA_RECOVERY',
                '2dc0c7a8-6d5b-468f-a26e-3d4bb0ebdd55'
            );
            """);

        assertThat(singleLong("SELECT count(*) FROM iam_identity_lifecycle_relay_state"))
            .isOne();
        assertThatThrownBy(() -> execute("""
            UPDATE iam_identity_lifecycle_outbox SET operation_type='DISABLE' WHERE user_id=22003
            """))
            .hasStackTraceContaining("iam_identity_lifecycle_outbox is append-only");
        assertThatThrownBy(() -> execute("""
            INSERT INTO iam_tenant_entry_host(entry_host,account_domain,tenant_id,status)
            VALUES('wrong-domain.admin.example.test','MERCHANT',22002,'ACTIVE')
            """))
            .hasStackTraceContaining("fk_iam_entry_host_tenant_domain");
        assertThatThrownBy(() -> execute("""
            INSERT INTO iam_tenant_entry_host(entry_host,account_domain,tenant_id,status)
            VALUES('Uppercase.admin.example.test','MERCHANT',22001,'ACTIVE')
            """))
            .hasStackTraceContaining("ck_iam_entry_host_canonical");
        assertThatThrownBy(() -> execute("""
            INSERT INTO iam_identity_lifecycle_outbox(
                user_id,tenant_id,realm,operation_type,idempotency_key
            ) VALUES(
                22003,22002,'AGENT','DISABLE',
                '0f0680d6-3ff1-4c2d-b1d8-12cf546f0dfa'
            )
            """))
            .hasStackTraceContaining("fk_iam_identity_outbox_user_realm");
    }

    @Test
    void missingGlobalUsernameConstraintBlocksExpandAtomically() throws Exception {
        flyway("20").migrate();
        execute("ALTER TABLE iam_authentication_credential "
            + "DROP CONSTRAINT uk_iam_authentication_username");

        assertThatThrownBy(() -> flyway(null).migrate())
            .hasStackTraceContaining(GLOBAL_USERNAME_GUARD);

        assertThat(currentSuccessfulVersion()).isEqualTo("20");
        assertThat(singleLong("""
            SELECT count(*) FROM information_schema.columns
             WHERE table_schema='public' AND table_name='iam_user'
               AND column_name='identity_version'
            """)).isZero();
        assertThat(singleLong("""
            SELECT count(*) FROM information_schema.tables
             WHERE table_schema='public' AND table_name='iam_tenant_entry_host'
            """)).isZero();
    }

    private static String currentSuccessfulVersion() throws Exception {
        return singleString("""
            SELECT version FROM flyway_schema_history
             WHERE success ORDER BY installed_rank DESC LIMIT 1
            """);
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

    private static String singleString(String sql) throws Exception {
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }

    private static Flyway flyway(String target) {
        var configuration = PostgresFlywayTestSupport.configure()
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
