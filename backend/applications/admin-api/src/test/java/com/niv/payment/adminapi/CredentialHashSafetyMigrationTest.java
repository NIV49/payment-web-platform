package com.niv.payment.adminapi;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class CredentialHashSafetyMigrationTest {
    private static final String CONSTRAINT_NAME = "ck_iam_authentication_bcrypt_hash";
    private static final String PAYLOAD =
        "N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

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

    @ParameterizedTest
    @ValueSource(strings = {
        "not-a-bcrypt-hash",
        "$2x$12$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy",
        "$2a$09$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy",
        "$2a$15$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy"
    })
    void unsafeHistoricalHashBlocksV13Atomically(String passwordHash) throws Exception {
        migrateTo("12");
        insertCredential(9_413_001L, passwordHash);

        assertThatThrownBy(CredentialHashSafetyMigrationTest::migrateToLatest)
            .hasStackTraceContaining(CONSTRAINT_NAME);

        assertThat(currentSuccessfulVersion()).isEqualTo("12");
        assertThat(constraintCount()).isZero();
        assertThat(singleLong("SELECT count(*) FROM iam_authentication_credential"
            + " WHERE user_id = 9413001")).isOne();
    }

    @Test
    void supportedHashesAndNullUpgradeWhileLaterUnsafeWritesAreRejected() throws Exception {
        migrateTo("12");
        insertCredential(9_413_101L, "$2a$10$" + PAYLOAD);
        insertCredential(9_413_102L, "$2b$12$" + PAYLOAD);
        insertCredential(9_413_103L, "$2y$14$" + PAYLOAD);
        insertCredential(9_413_104L, null);

        migrateToLatest();

        assertThat(currentSuccessfulVersion()).isEqualTo("13");
        assertThat(constraintCount()).isOne();
        assertThat(constraintValidated()).isTrue();
        assertThatThrownBy(() -> insertCredential(9_413_105L, "not-a-bcrypt-hash"))
            .hasStackTraceContaining(CONSTRAINT_NAME);
    }

    private static void insertCredential(long userId, String passwordHash) throws Exception {
        try (Connection connection = connection()) {
            try (PreparedStatement user = connection.prepareStatement("""
                INSERT INTO iam_user(id, idp_issuer, idp_subject, display_name, status)
                VALUES (?, 'migration-test', ?, 'Credential migration test', 'ACTIVE')
                """)) {
                user.setLong(1, userId);
                user.setString(2, "credential-migration-" + userId);
                user.executeUpdate();
            }
            try (PreparedStatement credential = connection.prepareStatement("""
                INSERT INTO iam_authentication_credential(user_id, username, password_hash, status)
                VALUES (?, ?, ?, 'ACTIVE')
                """)) {
                credential.setLong(1, userId);
                credential.setString(2, "credential-migration-" + userId);
                credential.setString(3, passwordHash);
                credential.executeUpdate();
            }
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
             WHERE conrelid = 'iam_authentication_credential'::regclass AND conname = '%s'
            """.formatted(CONSTRAINT_NAME));
    }

    private static boolean constraintValidated() throws Exception {
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                 SELECT convalidated FROM pg_constraint
                  WHERE conrelid = 'iam_authentication_credential'::regclass AND conname = '%s'
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
