package com.niv.payment.adminapi;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.flywaydb.database.postgresql.PostgreSQLConfigurationExtension;

public final class PostgresFlywayTestSupport {
    private PostgresFlywayTestSupport() {
    }

    public static FluentConfiguration configure() {
        var configuration = Flyway.configure();
        configuration.getConfigurationExtension(PostgreSQLConfigurationExtension.class)
            .setTransactionalLock(false);
        return configuration;
    }
}
