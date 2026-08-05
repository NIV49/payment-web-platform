package com.niv.payment.adminapi;

import org.flywaydb.core.Flyway;
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
class IamSequenceUpgradeMigrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:18.4-alpine@sha256:9a8afca54e7861fd90fab5fdf4c42477a6b1cb7d293595148e674e0a3181de15").asCompatibleSubstituteFor("postgres"))
        .withDatabaseName("payment_platform")
        .withUsername("payment_dev")
        .withPassword("payment_dev");

    @Test
    void upgradingFromV2NeverMovesTheSharedSequenceBehindExistingRows() throws Exception {
        migrateTo("2");

        long existingUserId;
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            try (ResultSet result = statement.executeQuery("SELECT nextval('iam_id_seq')")) {
                result.next();
                existingUserId = result.getLong(1);
            }
            statement.executeUpdate("""
                INSERT INTO iam_user(id,idp_issuer,idp_subject,display_name,status)
                VALUES (%d,'migration-test','existing-user','Existing User','ACTIVE')
                """.formatted(existingUserId));
        }

        migrateTo("7");

        try (Connection connection = connection(); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT nextval('iam_id_seq')")) {
            result.next();
            assertThat(result.getLong(1)).isGreaterThan(existingUserId);
        }

        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM iam_user WHERE id = " + existingUserId);
        }

        migrateTo(null);

        try (Connection connection = connection(); Statement statement = connection.createStatement();
             ResultSet requiredVersions = statement.executeQuery("""
                 SELECT count(*)
                   FROM information_schema.columns
                  WHERE table_schema='public' AND table_name='iam_permission_change_outbox'
                    AND column_name IN ('aggregate_version','schema_version') AND column_default IS NULL
                 """)) {
            requiredVersions.next();
            assertThat(requiredVersions.getInt(1)).isEqualTo(2);
        }

        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            try (ResultSet tenants = statement.executeQuery("SELECT count(*) FROM iam_tenant")) {
                tenants.next();
                assertThat(tenants.getInt(1)).isZero();
            }
            statement.executeUpdate("""
                INSERT INTO iam_tenant(id, tenant_code, tenant_name, tenant_type, status, account_domain)
                VALUES(nextval('iam_id_seq'), 'migration-test', 'Migration Test', 'PLATFORM', 'ACTIVE', 'PLATFORM')
                """);
            statement.executeUpdate("""
                INSERT INTO iam_permission_change_outbox(
                    id, tenant_id, aggregate_type, aggregate_ref, event_type, payload,
                    aggregate_version, schema_version, partition_key, trace_id
                )
                VALUES(nextval('iam_id_seq'),
                       (SELECT id FROM iam_tenant WHERE tenant_code='migration-test'),
                       'MEMBERSHIP', '1000', 'PermissionChanged',
                       '{}'::jsonb, 1, 1, '1000', 'migration-test-trace')
                """);
            try (ResultSet relay = statement.executeQuery("""
                SELECT status, attempts
                  FROM iam_permission_change_relay_state
                 WHERE event_record_id = (
                     SELECT id FROM iam_permission_change_outbox WHERE trace_id='migration-test-trace'
                 )
                """)) {
                relay.next();
                assertThat(relay.getString("status")).isEqualTo("PENDING");
                assertThat(relay.getInt("attempts")).isZero();
            }
            assertThatThrownBy(() -> statement.executeUpdate("""
                UPDATE iam_permission_change_outbox
                   SET event_type='Mutated'
                 WHERE trace_id='migration-test-trace'
                """))
                .hasMessageContaining("append-only");
        }
    }

    private static void migrateTo(String version) {
        var configuration = Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration");
        if (version != null) {
            configuration.target(version);
        }
        configuration.load().migrate();
    }

    private static Connection connection() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
