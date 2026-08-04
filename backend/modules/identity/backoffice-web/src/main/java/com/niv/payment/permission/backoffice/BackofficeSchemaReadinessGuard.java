package com.niv.payment.permission.backoffice;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.flywaydb.core.api.output.ValidateResult;
import org.springframework.boot.flyway.autoconfigure.FlywayProperties;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Read-only startup validation for the migrations packaged in this application binary.
 *
 * @see <a href="https://documentation.red-gate.com/flyway/reference/commands/validate">
 * Flyway validate</a>
 * @see <a href="https://documentation.red-gate.com/fd/flyway-ignore-migration-patterns-setting-277579002.html">
 * Flyway ignore migration patterns</a>
 */
public final class BackofficeSchemaReadinessGuard {
    private static final String NOT_READY_MESSAGE_PREFIX =
        "Database schema is not ready for this application binary; reason=";

    private static final Set<MigrationState> ACCEPTED_KNOWN_STATES =
        EnumSet.of(MigrationState.SUCCESS, MigrationState.OUT_OF_ORDER);
    public BackofficeSchemaReadinessGuard(DataSource dataSource,
                                          FlywayProperties properties,
                                          ClassLoader classLoader) {
        try {
            verify(createValidator(dataSource, properties, classLoader));
        } catch (FlywayException validationFailure) {
            throw notReady(FailureReason.VALIDATION_UNAVAILABLE);
        }
    }

    private static void verify(Flyway flyway) {
        ValidateResult validation = flyway.validateWithResult();
        MigrationInfoService info = flyway.info();

        List<MigrationInfo> versionedMigrations = Arrays.stream(info.all())
            .filter(MigrationInfo::isVersioned)
            .toList();

        if (info.pending().length != 0) {
            throw notReady(FailureReason.PENDING_MIGRATION);
        }
        if (versionedMigrations.isEmpty()) {
            throw notReady(FailureReason.NO_VERSIONED_MIGRATIONS);
        }

        FailureReason migrationFailure = versionedMigrations.stream()
            .map(BackofficeSchemaReadinessGuard::failureReason)
            .filter(reason -> reason != null)
            .findFirst()
            .orElse(null);
        if (migrationFailure != null) {
            throw notReady(migrationFailure);
        }
        if (!validation.validationSuccessful) {
            throw notReady(FailureReason.VALIDATION_FAILED);
        }
    }

    private static FailureReason failureReason(MigrationInfo migration) {
        MigrationState state = migration.getState();
        if (ACCEPTED_KNOWN_STATES.contains(state)) {
            if (!migration.isChecksumMatching()) {
                return FailureReason.CHECKSUM_MISMATCH;
            }
            if (!migration.isDescriptionMatching()) {
                return FailureReason.DESCRIPTION_MISMATCH;
            }
            if (!migration.isTypeMatching()) {
                return FailureReason.TYPE_MISMATCH;
            }
            return null;
        }

        return switch (state) {
            case FAILED -> FailureReason.FAILED_MIGRATION;
            case MISSING_SUCCESS, MISSING_FAILED -> FailureReason.MISSING_MIGRATION;
            case FUTURE_SUCCESS -> FailureReason.FUTURE_MIGRATION;
            case FUTURE_FAILED -> FailureReason.FUTURE_FAILED_MIGRATION;
            default -> FailureReason.INVALID_MIGRATION_STATE;
        };
    }

    private static Flyway createValidator(DataSource dataSource,
                                           FlywayProperties properties,
                                           ClassLoader classLoader) {
        FluentConfiguration configuration = Flyway.configure(classLoader)
            .dataSource(dataSource)
            .locations(properties.getLocations().toArray(String[]::new))
            .encoding(properties.getEncoding())
            .table(properties.getTable())
            .placeholderReplacement(properties.isPlaceholderReplacement())
            .placeholders(properties.getPlaceholders())
            .placeholderPrefix(properties.getPlaceholderPrefix())
            .placeholderSuffix(properties.getPlaceholderSuffix())
            .placeholderSeparator(properties.getPlaceholderSeparator())
            .scriptPlaceholderPrefix(properties.getScriptPlaceholderPrefix())
            .scriptPlaceholderSuffix(properties.getScriptPlaceholderSuffix())
            .sqlMigrationPrefix(properties.getSqlMigrationPrefix())
            .sqlMigrationSeparator(properties.getSqlMigrationSeparator())
            .sqlMigrationSuffixes(properties.getSqlMigrationSuffixes().toArray(String[]::new))
            .repeatableSqlMigrationPrefix(properties.getRepeatableSqlMigrationPrefix())
            .baselineVersion(properties.getBaselineVersion())
            .createSchemas(false)
            .cleanDisabled(true)
            .failOnMissingLocations(true)
            .validateMigrationNaming(true)
            .ignoreMigrationPatterns("*:future");

        if (StringUtils.hasText(properties.getDefaultSchema())) {
            configuration.defaultSchema(properties.getDefaultSchema());
        }
        if (!properties.getSchemas().isEmpty()) {
            configuration.schemas(properties.getSchemas().toArray(String[]::new));
        }
        if (properties.getDetectEncoding() != null) {
            configuration.detectEncoding(properties.getDetectEncoding());
        }
        return configuration.load();
    }

    public static String notReadyMessage(FailureReason reason) {
        return NOT_READY_MESSAGE_PREFIX + reason.name();
    }

    private static IllegalStateException notReady(FailureReason reason) {
        // Never include Flyway's raw error or connection details in the startup exception.
        return new IllegalStateException(notReadyMessage(reason));
    }

    public enum FailureReason {
        VALIDATION_UNAVAILABLE,
        PENDING_MIGRATION,
        NO_VERSIONED_MIGRATIONS,
        CHECKSUM_MISMATCH,
        DESCRIPTION_MISMATCH,
        TYPE_MISMATCH,
        FAILED_MIGRATION,
        MISSING_MIGRATION,
        FUTURE_MIGRATION,
        FUTURE_FAILED_MIGRATION,
        INVALID_MIGRATION_STATE,
        VALIDATION_FAILED
    }
}
