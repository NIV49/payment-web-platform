package com.niv.payment.permission.backoffice;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.flyway.autoconfigure.FlywayProperties;
import org.springframework.boot.sql.init.dependency.DatabaseInitializationDependencyConfigurer;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ResourceLoader;

import javax.sql.DataSource;

@EnableConfigurationProperties(FlywayProperties.class)
@Import(DatabaseInitializationDependencyConfigurer.class)
public class BackofficeSchemaReadinessConfiguration {
    @Bean
    @DependsOnDatabaseInitialization
    BackofficeSchemaReadinessGuard backofficeSchemaReadinessGuard(
        DataSource dataSource, FlywayProperties properties, ResourceLoader resourceLoader) {
        return new BackofficeSchemaReadinessGuard(
            dataSource, properties, resourceLoader.getClassLoader());
    }
}
