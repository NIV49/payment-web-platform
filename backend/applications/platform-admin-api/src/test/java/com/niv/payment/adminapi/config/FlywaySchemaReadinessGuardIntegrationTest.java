package com.niv.payment.adminapi.config;

import com.niv.payment.permission.backoffice.BackofficeSchemaReadinessGuard;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationInitializer;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class FlywaySchemaReadinessGuardIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse(
        "postgres:18.4-alpine@sha256:9a8afca54e7861fd90fab5fdf4c42477a6b1cb7d293595148e674e0a3181de15")
        .asCompatibleSubstituteFor("postgres"))
        .withDatabaseName("payment_platform")
        .withUsername("schema_guard_user")
        .withPassword("schema_guard_password");

    @BeforeEach
    void cleanDatabase() {
        migrationFlyway().clean();
    }

    @Test
    void oldSchemaWithFlywayDisabledFailsContextStartup() {
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration")
            .target("7")
            .load()
            .migrate();

        contextRunner(false).run(context -> {
            assertSchemaNotReady(context, BackofficeSchemaReadinessGuard.FailureReason.PENDING_MIGRATION);
            assertThat(appliedVersions()).containsExactly("1", "2", "3", "4", "5", "6", "7");
        });
    }

    @Test
    void latestSchemaWithFlywayDisabledPassesContextStartup() {
        migrationFlyway().migrate();

        contextRunner(false).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(BackofficeSchemaReadinessGuard.class);
            assertThat(context).doesNotHaveBean(Flyway.class);
        });
    }

    @Test
    void localFlywayMigrationCompletesBeforeTheReadinessGuard() {
        contextRunner(true)
            .withPropertyValues("spring.profiles.active=local")
            .run(context -> {
                assertThat(context).hasNotFailed();
                String[] initializerNames = context.getBeanNamesForType(FlywayMigrationInitializer.class);
                String[] guardNames = context.getBeanNamesForType(BackofficeSchemaReadinessGuard.class);
                assertThat(initializerNames).isNotEmpty();
                assertThat(guardNames).hasSize(1);
                assertThat(Arrays.asList(context.getBeanFactory()
                    .getBeanDefinition(guardNames[0]).getDependsOn()))
                    .containsAll(Arrays.asList(initializerNames));
                assertThat(context.getBean(Flyway.class).info().pending()).isEmpty();
            });
    }

    @Test
    void knownMigrationChecksumMismatchFailsContextStartup() {
        migrationFlyway().migrate();
        executeUpdate("""
            UPDATE flyway_schema_history
               SET checksum = checksum + 1
             WHERE installed_rank = (
                   SELECT MAX(installed_rank)
                     FROM flyway_schema_history
                    WHERE success
             )
            """);

        contextRunner(false).run(context -> assertSchemaNotReady(
            context, BackofficeSchemaReadinessGuard.FailureReason.CHECKSUM_MISMATCH));
    }

    @Test
    void failedKnownMigrationFailsContextStartup() {
        migrationFlyway().migrate();
        executeUpdate("""
            UPDATE flyway_schema_history
               SET success = FALSE
             WHERE installed_rank = (
                   SELECT MAX(installed_rank)
                     FROM flyway_schema_history
                    WHERE success
             )
            """);

        contextRunner(false).run(context -> assertSchemaNotReady(
            context, BackofficeSchemaReadinessGuard.FailureReason.FAILED_MIGRATION));
    }

    @Test
    void missingMigrationInsideBinaryVersionRangeFailsContextStartup() {
        migrationFlyway().migrate();
        insertSchemaHistory("7.5", "missing migration", true);

        contextRunner(false).run(context -> assertSchemaNotReady(
            context, BackofficeSchemaReadinessGuard.FailureReason.MISSING_MIGRATION));
    }

    @Test
    void successfulFutureMigrationIsRejectedWithoutMixedVersionCompatibilityEvidence() {
        migrationFlyway().migrate();
        insertSchemaHistory("9999", "future migration", true);

        contextRunner(false).run(context -> assertSchemaNotReady(
            context, BackofficeSchemaReadinessGuard.FailureReason.FUTURE_MIGRATION));
    }

    @Test
    void failedFutureMigrationStillFailsContextStartup() {
        migrationFlyway().migrate();
        insertSchemaHistory("9999", "failed future migration", false);

        contextRunner(false).run(context -> assertSchemaNotReady(
            context, BackofficeSchemaReadinessGuard.FailureReason.FUTURE_FAILED_MIGRATION));
    }

    @Test
    void flywayFailureIsReducedToASanitizedReasonCode() {
        String missingLocation = "classpath:db/secret-missing-migrations";

        contextRunner(false)
            .withPropertyValues("spring.flyway.locations=" + missingLocation)
            .run(context -> {
                assertSchemaNotReady(context, BackofficeSchemaReadinessGuard.FailureReason.VALIDATION_UNAVAILABLE);
                assertThat(causeChain(context.getStartupFailure()))
                    .doesNotContain(missingLocation)
                    .doesNotContain("FlywayException");
            });
    }

    private ApplicationContextRunner contextRunner(boolean flywayEnabled) {
        return new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                DataSourceAutoConfiguration.class,
                FlywayAutoConfiguration.class))
            .withUserConfiguration(FlywaySchemaReadinessConfiguration.class)
            .withPropertyValues(
                "spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "spring.datasource.username=" + POSTGRES.getUsername(),
                "spring.datasource.password=" + POSTGRES.getPassword(),
                "spring.datasource.driver-class-name=org.postgresql.Driver",
                "spring.flyway.enabled=" + flywayEnabled,
                "spring.flyway.locations=classpath:db/migration");
    }

    private static Flyway migrationFlyway() {
        return Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration")
            .cleanDisabled(false)
            .load();
    }

    private static void assertSchemaNotReady(AssertableApplicationContext context,
                                             BackofficeSchemaReadinessGuard.FailureReason expectedReason) {
        assertThat(context).hasFailed();
        Throwable startupFailure = context.getStartupFailure();
        assertThat(startupFailure)
            .hasRootCauseMessage(BackofficeSchemaReadinessGuard.notReadyMessage(expectedReason));
        assertThat(causeChain(startupFailure))
            .doesNotContain(POSTGRES.getJdbcUrl())
            .doesNotContain(POSTGRES.getUsername())
            .doesNotContain(POSTGRES.getPassword());
    }

    private static String causeChain(Throwable failure) {
        StringBuilder messages = new StringBuilder();
        Throwable current = failure;
        while (current != null) {
            messages.append(current.getClass().getName())
                .append(':')
                .append(current.getMessage())
                .append('\n');
            current = current.getCause();
        }
        return messages.toString();
    }

    private static void insertSchemaHistory(String version, String description, boolean success) {
        executeUpdate("""
            INSERT INTO flyway_schema_history (
                installed_rank,
                version,
                description,
                type,
                script,
                checksum,
                installed_by,
                execution_time,
                success
            )
            SELECT COALESCE(MAX(installed_rank), 0) + 1,
                   '%s',
                   '%s',
                   'SQL',
                   'V%s__synthetic_test_migration.sql',
                   1,
                   CURRENT_USER,
                   1,
                   %s
              FROM flyway_schema_history
            """.formatted(version, description, version.replace('.', '_'), success));
    }

    private static List<String> appliedVersions() {
        List<String> versions = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                 SELECT version
                   FROM flyway_schema_history
                  WHERE success
                  ORDER BY installed_rank
                 """)) {
            while (resultSet.next()) {
                versions.add(resultSet.getString(1));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to inspect test schema history", exception);
        }
        return versions;
    }

    private static void executeUpdate(String sql) {
        try (Connection connection = DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to arrange test schema history", exception);
        }
    }
}
