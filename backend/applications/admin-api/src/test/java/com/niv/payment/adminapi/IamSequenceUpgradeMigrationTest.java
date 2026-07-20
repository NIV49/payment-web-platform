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
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16.14-alpine@sha256:57c72fd2a128e416c7fcc499958864df5301e940bca0a56f58fddf30ffc07777").asCompatibleSubstituteFor("postgres"))
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

        migrateTo(null);

        try (Connection connection = connection(); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT nextval('iam_id_seq')")) {
            result.next();
            assertThat(result.getLong(1)).isGreaterThan(existingUserId);
        }

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
            statement.executeUpdate("""
                INSERT INTO iam_permission_change_outbox(
                    id, tenant_id, aggregate_type, aggregate_ref, event_type, payload,
                    aggregate_version, schema_version, partition_key, trace_id
                )
                VALUES(nextval('iam_id_seq'), 1, 'MEMBERSHIP', '1000', 'PermissionChanged',
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
