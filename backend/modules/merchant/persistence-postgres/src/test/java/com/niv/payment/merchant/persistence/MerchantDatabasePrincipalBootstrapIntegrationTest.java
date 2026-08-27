package com.niv.payment.merchant.persistence;

import org.flywaydb.core.Flyway;
import org.flywaydb.database.postgresql.PostgreSQLConfigurationExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@Timeout(value = 90, unit = TimeUnit.SECONDS)
class MerchantDatabasePrincipalBootstrapIntegrationTest {
    private static final Path BACKEND_ROOT = locateBackendRoot();
    private static final Path ROLE_BOOTSTRAP = BACKEND_ROOT.resolve(
        "scripts/mch001-bootstrap-registration-rotation-role.sql");
    private static final Path MIGRATION_PREFLIGHT = BACKEND_ROOT.resolve(
        "scripts/mch001-migration-principal-preflight.sql");
    private static final Path V33 = BACKEND_ROOT.resolve(
        "modules/merchant/persistence-postgres/src/main/resources/db/migration/"
            + "V33__isolate_merchant_registration_rotation_role.sql");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse(
        "postgres:18.4-alpine@sha256:9a8afca54e7861fd90fab5fdf4c42477a6b1cb7d293595148e674e0a3181de15")
        .asCompatibleSubstituteFor("postgres"))
        .withDatabaseName("payment_platform")
        .withUsername("payment_dev")
        .withPassword("payment_dev");

    @Test
    void clusterBootstrapPreventsPg18CreatorMembershipAndNocreaterolePrincipalMigrates()
        throws Exception {
        executeAdmin("""
            CREATE ROLE mch_v33_creator LOGIN PASSWORD 'mch-v33-creator-test'
              NOSUPERUSER NOCREATEDB CREATEROLE NOREPLICATION NOBYPASSRLS;
            """);
        try (Connection creator = connection("mch_v33_creator", "mch-v33-creator-test")) {
            creator.setAutoCommit(false);
            assertThatThrownBy(() -> execute(creator, Files.readString(V33)))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("registration rotation role has preexisting memberships");
            creator.rollback();
        }
        assertThat(adminLong("""
            SELECT count(*) FROM pg_roles
             WHERE rolname='payment_merchant_registration_rotation'
            """)).isZero();

        executeAdmin(Files.readString(ROLE_BOOTSTRAP));
        executeAdmin(Files.readString(ROLE_BOOTSTRAP));
        assertCanonicalUnownedCapabilityRole();
        try (Connection creator = connection("mch_v33_creator", "mch-v33-creator-test")) {
            assertThatThrownBy(() -> execute(creator, Files.readString(MIGRATION_PREFLIGHT)))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("must be NOSUPERUSER and NOCREATEROLE");
        }
        executeAdmin("""
            CREATE ROLE mch_migration LOGIN PASSWORD 'mch-migration-test'
              NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
            ALTER DATABASE payment_platform OWNER TO mch_migration;
            ALTER SCHEMA public OWNER TO mch_migration;
            """);

        try (Connection migration = connection("mch_migration", "mch-migration-test")) {
            execute(migration, Files.readString(MIGRATION_PREFLIGHT));
        }
        migrateAs("mch_migration", "mch-migration-test");

        assertThat(adminString("""
            SELECT version FROM flyway_schema_history
             WHERE success ORDER BY installed_rank DESC LIMIT 1
            """)).isEqualTo("43");
        assertThat(adminLong("""
            SELECT count(*) FROM pg_roles
             WHERE rolname='mch_migration' AND NOT rolsuper AND NOT rolcreaterole
            """)).isEqualTo(1);
        assertCanonicalUnownedCapabilityRole();
    }

    private static void assertCanonicalUnownedCapabilityRole() throws SQLException {
        assertThat(adminLong("""
            SELECT count(*) FROM pg_roles
             WHERE rolname='payment_merchant_registration_rotation'
               AND NOT rolcanlogin AND NOT rolsuper AND rolinherit
               AND NOT rolcreaterole AND NOT rolcreatedb
               AND NOT rolreplication AND NOT rolbypassrls
            """)).isEqualTo(1);
        assertThat(adminLong("""
            SELECT count(*) FROM pg_auth_members membership
              JOIN pg_roles capability
                ON capability.oid IN (membership.roleid,membership.member)
             WHERE capability.rolname='payment_merchant_registration_rotation'
            """)).isZero();
    }

    private static void migrateAs(String username, String password) {
        var configuration = Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), username, password)
            .locations(
                filesystem(BACKEND_ROOT.resolve(
                    "modules/identity/persistence-postgres/src/main/resources/db/migration")),
                filesystem(BACKEND_ROOT.resolve(
                    "modules/system-dictionary/src/main/resources/db/migration")),
                filesystem(BACKEND_ROOT.resolve(
                    "modules/merchant/persistence-postgres/src/main/resources/db/migration")));
        configuration.getConfigurationExtension(PostgreSQLConfigurationExtension.class)
            .setTransactionalLock(false);
        configuration.load().migrate();
    }

    private static String filesystem(Path path) {
        return "filesystem:" + path.toAbsolutePath().normalize();
    }

    private static Connection adminConnection() throws SQLException {
        return connection(POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static Connection connection(String username, String password) throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), username, password);
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static void executeAdmin(String sql) throws SQLException {
        try (Connection connection = adminConnection()) {
            execute(connection, sql);
        }
    }

    private static long adminLong(String sql) throws SQLException {
        try (Connection connection = adminConnection(); Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            assertThat(rows.next()).isTrue();
            return rows.getLong(1);
        }
    }

    private static String adminString(String sql) throws SQLException {
        try (Connection connection = adminConnection(); Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            assertThat(rows.next()).isTrue();
            return rows.getString(1);
        }
    }

    private static Path locateBackendRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isDirectory(current.resolve("modules/identity/persistence-postgres"))) {
                return current;
            }
            if (Files.isDirectory(current.resolve("backend/modules/identity/persistence-postgres"))) {
                return current.resolve("backend");
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Cannot locate backend root for cluster bootstrap tests");
    }
}
