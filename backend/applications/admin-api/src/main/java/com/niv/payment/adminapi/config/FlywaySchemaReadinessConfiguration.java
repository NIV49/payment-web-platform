package com.niv.payment.adminapi.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.flyway.autoconfigure.FlywayProperties;
import org.springframework.boot.sql.init.dependency.DatabaseInitializationDependencyConfigurer;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ResourceLoader;

import javax.sql.DataSource;

/**
 * Installs the schema guard during context refresh, before the web server starts accepting traffic.
 *
 * @see <a href="https://docs.spring.io/spring-boot/how-to/data-initialization.html">
 * Spring Boot database initialization dependencies</a>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(FlywayProperties.class)
@Import(DatabaseInitializationDependencyConfigurer.class)
class FlywaySchemaReadinessConfiguration {
    @Bean
    @DependsOnDatabaseInitialization
    FlywaySchemaReadinessGuard flywaySchemaReadinessGuard(DataSource dataSource,
                                                          FlywayProperties properties,
                                                          ResourceLoader resourceLoader) {
        return new FlywaySchemaReadinessGuard(dataSource, properties, resourceLoader.getClassLoader());
    }
}
