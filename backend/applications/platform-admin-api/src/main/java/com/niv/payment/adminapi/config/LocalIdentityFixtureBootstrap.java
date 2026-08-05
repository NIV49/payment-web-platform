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

            initializeCredential(100, "admin");
            initializeCredential(200, "merchant-admin");
            initializeCredential(300, "agent-admin");

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
                       AND tenant.account_domain = 'PLATFORM'
                       AND tenant.status = 'ACTIVE'
                       AND department.id = 10 AND department.department_code = 'head-office'
                       AND department.status = 'ACTIVE'
                       AND membership.id = 1000 AND membership.status = 'ACTIVE'
                       AND user_account.id = 100 AND user_account.idp_issuer = 'local'
                       AND user_account.account_domain = 'PLATFORM'
                       AND user_account.idp_subject = 'admin' AND user_account.status = 'ACTIVE'
                       AND credential.username = 'admin' AND credential.status = 'ACTIVE'
                       AND credential.account_domain = 'PLATFORM'
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
                    AND (id IN (6000, 6001, 6002, 6003, 6004, 6010, 6011, 6012)
                         OR id BETWEEN 6020 AND 6040)) AS menus,
                (SELECT count(*) FROM iam_role_menu
                  WHERE tenant_id = 1 AND role_id = 2000
                    AND menu_id IN (6000, 6001, 6002, 6003, 6004, 6010, 6011, 6012)) AS role_menus,
                (SELECT count(*)
                   FROM iam_membership membership
                   JOIN iam_tenant tenant ON tenant.id = membership.tenant_id
                   JOIN iam_user user_account ON user_account.id = membership.user_id
                   JOIN iam_authentication_credential credential ON credential.user_id = user_account.id
                   JOIN iam_membership_role membership_role
                     ON membership_role.tenant_id = tenant.id
                    AND membership_role.membership_id = membership.id
                   JOIN iam_role role
                     ON role.tenant_id = tenant.id AND role.id = membership_role.role_id
                  WHERE (tenant.id = 2 AND tenant.account_domain = 'MERCHANT'
                         AND membership.id = 2100 AND membership.account_domain = 'MERCHANT'
                         AND user_account.id = 200 AND user_account.account_domain = 'MERCHANT'
                         AND credential.username = 'merchant-admin'
                         AND credential.account_domain = 'MERCHANT' AND role.id = 2200)
                     OR (tenant.id = 3 AND tenant.account_domain = 'AGENT'
                         AND membership.id = 3100 AND membership.account_domain = 'AGENT'
                         AND user_account.id = 300 AND user_account.account_domain = 'AGENT'
                         AND credential.username = 'agent-admin'
                         AND credential.account_domain = 'AGENT' AND role.id = 3200)
                    AND tenant.status = 'ACTIVE'
                    AND membership.status = 'ACTIVE'
                    AND user_account.status = 'ACTIVE'
                    AND credential.status = 'ACTIVE'
                    AND credential.password_hash IS NOT NULL
                    AND role.status = 'ACTIVE' AND role.deleted_at IS NULL
                    AND role.system_role AND NOT role.assignable
                    AND EXISTS (
                        SELECT 1
                          FROM iam_role_grant portal_grant
                          JOIN iam_permission portal_permission
                            ON portal_permission.id = portal_grant.permission_id
                           AND portal_permission.status = 'ACTIVE'
                           AND portal_permission.permission_code = CASE role.id
                               WHEN 2200 THEN 'backoffice:merchant-access'
                               WHEN 3200 THEN 'backoffice:agent-access'
                           END
                           AND portal_permission.risk_level = 'NORMAL'
                           AND portal_permission.cross_tenant_mode = 'SAME_TENANT_ONLY'
                           AND portal_permission.required_dimensions = ARRAY['TENANT']::varchar(32)[]
                           AND NOT portal_permission.requires_step_up
                           AND NOT portal_permission.requires_approval
                          JOIN iam_grant_dimension portal_dimension
                            ON portal_dimension.grant_id = portal_grant.id
                           AND portal_dimension.dimension_code = 'TENANT'
                           AND portal_dimension.scope_mode = 'TENANT_ALL'
                         WHERE portal_grant.tenant_id = tenant.id
                           AND portal_grant.role_id = role.id
                           AND portal_grant.grant_key = 'system-backoffice-access'
                           AND portal_grant.status = 'ACTIVE'
                           AND portal_grant.valid_from IS NULL
                           AND portal_grant.valid_until IS NULL
                           AND (SELECT count(*) FROM iam_grant_dimension
                                WHERE grant_id = portal_grant.id) = 1
                           AND NOT EXISTS (
                               SELECT 1 FROM iam_grant_target portal_target
                                WHERE portal_target.dimension_id = portal_dimension.id
                           )
                           AND NOT EXISTS (
                               SELECT 1
                                 FROM iam_role_grant extra_portal_grant
                                 JOIN iam_permission extra_portal_permission
                                   ON extra_portal_permission.id = extra_portal_grant.permission_id
                                WHERE extra_portal_grant.tenant_id = portal_grant.tenant_id
                                  AND extra_portal_grant.role_id = portal_grant.role_id
                                  AND extra_portal_grant.status = 'ACTIVE'
                                  AND extra_portal_grant.id <> portal_grant.id
                                  AND extra_portal_permission.permission_code IN (
                                      'backoffice:platform-access',
                                      'backoffice:merchant-access',
                                      'backoffice:agent-access'
                                  )
                           )
                    )) AS isolated_identities,
                (SELECT count(*) FROM iam_role_menu
                  WHERE (tenant_id = 2 AND role_id = 2200 AND menu_id IN (6200, 6201))
                     OR (tenant_id = 3 AND role_id = 3200 AND menu_id IN (6300, 6301))) AS isolated_role_menus
                """, (result, rowNumber) -> new FixtureReadiness(
                    result.getLong("identity_rows"),
                    result.getLong("grants"),
                    result.getLong("dimensions"),
                    result.getLong("targets"),
                    result.getLong("menus"),
                    result.getLong("role_menus"),
                    result.getLong("isolated_identities"),
                    result.getLong("isolated_role_menus")));
            if (ready == null || !ready.complete()) {
                throw new IllegalStateException("The local identity fixture is incomplete or inactive");
            }
        });
    }

    private void initializeCredential(long userId, String username) {
        String storedPasswordHash = jdbc.queryForObject("""
            SELECT password_hash
              FROM iam_authentication_credential
             WHERE user_id = ? AND username = ? AND status = 'ACTIVE'
            """, String.class, userId, username);
        if (storedPasswordHash == null) {
            int updated = jdbc.update("""
                UPDATE iam_authentication_credential
                   SET password_hash = ?, updated_at = now(), row_version = row_version + 1
                 WHERE user_id = ? AND username = ? AND status = 'ACTIVE' AND password_hash IS NULL
                """, passwordEncoder.encode(bootstrapPassword), userId, username);
            if (updated != 1) {
                throw new IllegalStateException(
                    "The local identity fixture credential could not be initialized atomically");
            }
            storedPasswordHash = jdbc.queryForObject("""
                SELECT password_hash
                  FROM iam_authentication_credential
                 WHERE user_id = ? AND username = ? AND status = 'ACTIVE'
                """, String.class, userId, username);
        }
        if (storedPasswordHash == null
            || !passwordEncoder.matches(bootstrapPassword, storedPasswordHash)) {
            throw new IllegalStateException(
                "The existing local fixture password does not match payment.bootstrap-password");
        }
    }

    private record FixtureReadiness(long identityRows,
                                    long grants,
                                    long dimensions,
                                    long targets,
                                    long menus,
                                    long roleMenus,
                                    long isolatedIdentities,
                                    long isolatedRoleMenus) {
        boolean complete() {
            return identityRows == 1
                && grants == 20
                && dimensions == 20
                && targets == 0
                && menus == 29
                && roleMenus == 8
                && isolatedIdentities == 2
                && isolatedRoleMenus == 4;
        }
    }
}
