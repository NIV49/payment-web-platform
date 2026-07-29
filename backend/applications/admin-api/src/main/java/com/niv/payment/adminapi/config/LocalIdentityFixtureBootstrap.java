package com.niv.payment.adminapi.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

@Component
@Profile("local")
@Order(Ordered.HIGHEST_PRECEDENCE)
final class LocalIdentityFixtureBootstrap implements ApplicationRunner {
    private static final String FIXTURE_SCRIPT = "db/local/iam-local-bootstrap.sql";

    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final BCryptPasswordEncoder passwordEncoder;
    private final String bootstrapPassword;

    LocalIdentityFixtureBootstrap(DataSource dataSource,
                                  JdbcTemplate jdbc,
                                  PlatformTransactionManager transactionManager,
                                  BCryptPasswordEncoder passwordEncoder,
                                  @Value("${payment.bootstrap-password}")
                                  String bootstrapPassword) {
        this.dataSource = dataSource;
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.passwordEncoder = passwordEncoder;
        this.bootstrapPassword = bootstrapPassword;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        if (bootstrapPassword == null || bootstrapPassword.isBlank()) {
            throw new IllegalStateException(
                "The local profile requires an explicit payment.bootstrap-password");
        }

        transactions.executeWithoutResult(ignored -> {
            ResourceDatabasePopulator fixture = new ResourceDatabasePopulator(
                new ClassPathResource(FIXTURE_SCRIPT));
            fixture.setSeparator("@@");
            fixture.setContinueOnError(false);
            DatabasePopulatorUtils.execute(fixture, dataSource);

            String storedPasswordHash = jdbc.queryForObject("""
                SELECT password_hash
                  FROM iam_authentication_credential
                 WHERE user_id = 100 AND username = 'admin' AND status = 'ACTIVE'
                """, String.class);
            if (storedPasswordHash == null) {
                int updated = jdbc.update("""
                    UPDATE iam_authentication_credential
                       SET password_hash = ?, updated_at = now(), row_version = row_version + 1
                     WHERE user_id = 100
                       AND username = 'admin'
                       AND status = 'ACTIVE'
                       AND password_hash IS NULL
                    """, passwordEncoder.encode(bootstrapPassword));
                if (updated != 1) {
                    throw new IllegalStateException(
                        "The local identity fixture credential could not be initialized atomically");
                }
                storedPasswordHash = jdbc.queryForObject("""
                    SELECT password_hash
                      FROM iam_authentication_credential
                     WHERE user_id = 100 AND username = 'admin' AND status = 'ACTIVE'
                    """, String.class);
            }
            if (storedPasswordHash == null
                || !passwordEncoder.matches(bootstrapPassword, storedPasswordHash)) {
                throw new IllegalStateException(
                    "The existing local fixture password does not match payment.bootstrap-password");
            }

            FixtureReadiness ready = jdbc.queryForObject("""
                SELECT (
                    SELECT count(*)
                      FROM iam_tenant tenant
                      JOIN iam_department department ON department.tenant_id = tenant.id
                      JOIN iam_membership membership
                        ON membership.tenant_id = tenant.id
                       AND membership.department_id = department.id
                      JOIN iam_user user_account ON user_account.id = membership.user_id
                      JOIN iam_authentication_credential credential
                        ON credential.user_id = user_account.id
                      JOIN iam_role role ON role.tenant_id = tenant.id
                      JOIN iam_membership_role membership_role
                        ON membership_role.tenant_id = tenant.id
                       AND membership_role.membership_id = membership.id
                       AND membership_role.role_id = role.id
                     WHERE tenant.id = 1 AND tenant.tenant_code = 'platform'
                       AND tenant.status = 'ACTIVE'
                       AND department.id = 10 AND department.department_code = 'head-office'
                       AND department.status = 'ACTIVE'
                       AND membership.id = 1000 AND membership.status = 'ACTIVE'
                       AND user_account.id = 100 AND user_account.idp_issuer = 'local'
                       AND user_account.idp_subject = 'admin' AND user_account.status = 'ACTIVE'
                       AND credential.username = 'admin' AND credential.status = 'ACTIVE'
                       AND credential.password_hash IS NOT NULL
                       AND role.id = 2000 AND role.role_code = 'platform-admin'
                       AND role.system_role AND NOT role.assignable AND role.status = 'ACTIVE'
                ) AS identity_rows,
                (SELECT count(*) FROM iam_role_grant
                  WHERE tenant_id = 1 AND role_id = 2000) AS grants,
                (SELECT count(*)
                   FROM iam_grant_dimension dimension_row
                   JOIN iam_role_grant grant_row ON grant_row.id = dimension_row.grant_id
                  WHERE grant_row.tenant_id = 1 AND grant_row.role_id = 2000) AS dimensions,
                (SELECT count(*)
                   FROM iam_grant_target target
                   JOIN iam_grant_dimension dimension_row ON dimension_row.id = target.dimension_id
                   JOIN iam_role_grant grant_row ON grant_row.id = dimension_row.grant_id
                  WHERE grant_row.tenant_id = 1 AND grant_row.role_id = 2000) AS targets,
                (SELECT count(*) FROM iam_menu
                  WHERE tenant_id = 1
                    AND id IN (6000, 6001, 6002, 6003, 6004, 6010, 6011, 6012)) AS menus,
                (SELECT count(*) FROM iam_role_menu
                  WHERE tenant_id = 1 AND role_id = 2000
                    AND menu_id IN (6000, 6001, 6002, 6003, 6004, 6010, 6011, 6012)) AS role_menus
                """, (result, rowNumber) -> new FixtureReadiness(
                    result.getLong("identity_rows"),
                    result.getLong("grants"),
                    result.getLong("dimensions"),
                    result.getLong("targets"),
                    result.getLong("menus"),
                    result.getLong("role_menus")));
            if (ready == null || !ready.complete()) {
                throw new IllegalStateException("The local identity fixture is incomplete or inactive");
            }
        });
    }

    private record FixtureReadiness(long identityRows,
                                    long grants,
                                    long dimensions,
                                    long targets,
                                    long menus,
                                    long roleMenus) {
        boolean complete() {
            return identityRows == 1
                && grants == 14
                && dimensions == 14
                && targets == 0
                && menus == 8
                && roleMenus == 8;
        }
    }
}
