package com.niv.payment.adminapi.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
@Profile({"local", "iam002-local"})
@Order(Ordered.HIGHEST_PRECEDENCE)
final class LocalIdentityFixtureBootstrap implements ApplicationRunner {
    private static final String FIXTURE_SCRIPT = "db/local/iam-local-bootstrap.sql";
    private static final String PERSISTED_STAGE_BEGIN = "-- IAM002_PERSISTED_MCH003_BEGIN";
    private static final String PERSISTED_STAGE_END = "-- IAM002_PERSISTED_MCH003_END";
    private static final String DICTIONARY_SAMPLE_SCRIPT =
        "db/local/system-dictionary-sample.sql";

    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final BCryptPasswordEncoder passwordEncoder;
    private final String bootstrapPassword;
    private final boolean persistedLocalRuntime;

    LocalIdentityFixtureBootstrap(DataSource dataSource,
                                  JdbcTemplate jdbc,
                                  PlatformTransactionManager transactionManager,
                                  BCryptPasswordEncoder passwordEncoder,
                                  Environment environment,
                                  @Value("${payment.bootstrap-password}")
                                  String bootstrapPassword) {
        this.dataSource = dataSource;
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.passwordEncoder = passwordEncoder;
        this.bootstrapPassword = bootstrapPassword;
        this.persistedLocalRuntime = environment.acceptsProfiles(Profiles.of("iam002-local"));
    }

    @Override
    public void run(ApplicationArguments arguments) {
        if (bootstrapPassword == null || bootstrapPassword.isBlank()) {
            throw new IllegalStateException(
                "The local profile requires an explicit payment.bootstrap-password");
        }

        transactions.executeWithoutResult(ignored -> {
            ResourceDatabasePopulator fixture = new ResourceDatabasePopulator(fixtureResource());
            fixture.setSeparator("@@");
            fixture.setContinueOnError(false);
            DatabasePopulatorUtils.execute(fixture, dataSource);

            if (!persistedLocalRuntime) {
                ResourceDatabasePopulator dictionarySample = new ResourceDatabasePopulator(
                    new ClassPathResource(DICTIONARY_SAMPLE_SCRIPT));
                dictionarySample.setContinueOnError(false);
                DatabasePopulatorUtils.execute(dictionarySample, dataSource);
            }

            if (!persistedLocalRuntime) {
                initializeCredential(100, "admin@platform.localhost");
                initializeCredential(200, "admin@merchant.localhost");
                initializeCredential(300, "admin@agent.localhost");
            }
            initializeOrSynchronizeReviewerCredential();

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
                       AND user_account.id = 100
                       AND user_account.idp_issuer IN ('local', 'local:platform')
                       AND user_account.account_domain = 'PLATFORM'
                       AND user_account.idp_subject = 'admin@platform.localhost' AND user_account.status = 'ACTIVE'
                       AND credential.username = 'admin@platform.localhost' AND credential.status = 'ACTIVE'
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
                         OR id BETWEEN 6020 AND 6040
                         OR route_name IN ('MerchantManagement', 'MerchantList',
                             'MerchantReview', 'MerchantDisable', 'MerchantEnable',
                             'MerchantTerminate', 'MerchantEdit', 'MerchantCreate'))) AS menus,
                (SELECT count(*) FROM iam_role_menu
                  WHERE tenant_id = 1 AND role_id = 2000 AND (
                    menu_id IN (6000, 6001, 6002, 6003, 6004, 6010, 6011, 6012)
                    OR menu_id IN (SELECT id FROM iam_menu
                                    WHERE tenant_id=1 AND route_name IN (
                                      'MerchantManagement', 'MerchantList', 'MerchantReview',
                                      'MerchantDisable', 'MerchantEnable', 'MerchantTerminate',
                                      'MerchantEdit', 'MerchantCreate'))
                  )) AS role_menus,
                (SELECT count(*)
                   FROM iam_user reviewer
                   JOIN iam_authentication_credential credential ON credential.user_id=reviewer.id
                   JOIN iam_membership membership ON membership.user_id=reviewer.id
                   JOIN iam_membership_role assignment
                     ON assignment.tenant_id=membership.tenant_id
                    AND assignment.membership_id=membership.id
                  WHERE reviewer.id=101
                    AND reviewer.idp_issuer IN ('local', 'local:platform')
                    AND reviewer.idp_subject='reviewer@platform.localhost'
                    AND reviewer.account_domain='PLATFORM' AND reviewer.status='ACTIVE'
                    AND credential.username='reviewer@platform.localhost'
                    AND credential.account_domain='PLATFORM' AND credential.status='ACTIVE'
                    AND credential.password_hash IS NOT NULL
                    AND membership.id=1001 AND membership.tenant_id=1
                    AND membership.account_domain='PLATFORM' AND membership.status='ACTIVE'
                    AND membership.permission_version>=1
                    AND assignment.role_id=2000) AS reviewer_rows,
                (SELECT count(*) FROM iam_tenant tenant
                  WHERE (tenant.tenant_code='local-merchant-candidate'
                         OR tenant.tenant_code ~
                            '^local-merchant-candidate-([2-9]|[1-9][0-9]{1,5})$')
                    AND tenant.tenant_name=CASE
                      WHEN tenant.tenant_code='local-merchant-candidate'
                        THEN 'Local Merchant Candidate'
                      ELSE 'Local Merchant Candidate ' || substring(tenant.tenant_code FROM 26)
                    END
                    AND tenant.tenant_type='DIRECT_MERCHANT'
                    AND tenant.account_domain='MERCHANT' AND tenant.status='ACTIVE'
                    AND NOT EXISTS (SELECT 1 FROM iam_department WHERE tenant_id=tenant.id)
                    AND NOT EXISTS (SELECT 1 FROM iam_membership WHERE tenant_id=tenant.id)
                    AND NOT EXISTS (SELECT 1 FROM iam_role WHERE tenant_id=tenant.id)
                    AND NOT EXISTS (SELECT 1 FROM merchant WHERE tenant_id=tenant.id)
                ) AS candidate_rows,
                (SELECT count(*) FROM merchant
                  WHERE tenant_id=2 AND account_domain='MERCHANT'
                ) AS bound_merchant_rows,
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
                         AND credential.username = 'admin@merchant.localhost'
                         AND credential.account_domain = 'MERCHANT' AND role.id = 2200)
                     OR (tenant.id = 3 AND tenant.account_domain = 'AGENT'
                         AND membership.id = 3100 AND membership.account_domain = 'AGENT'
                         AND user_account.id = 300 AND user_account.account_domain = 'AGENT'
                         AND credential.username = 'admin@agent.localhost'
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
                    result.getLong("reviewer_rows"),
                    result.getLong("candidate_rows"),
                    result.getLong("bound_merchant_rows"),
                    result.getLong("isolated_identities"),
                    result.getLong("isolated_role_menus")));
            if (ready == null || !ready.complete()) {
                throw new IllegalStateException("The local identity fixture is incomplete or inactive");
            }
        });
    }

    private Resource fixtureResource() {
        ClassPathResource fixture = new ClassPathResource(FIXTURE_SCRIPT);
        if (!persistedLocalRuntime) {
            return fixture;
        }
        try {
            String script = fixture.getContentAsString(StandardCharsets.UTF_8);
            int begin = script.indexOf(PERSISTED_STAGE_BEGIN);
            int end = script.indexOf(PERSISTED_STAGE_END);
            if (begin < 0 || end <= begin) {
                throw new IllegalStateException(
                    "The iam002-local persisted fixture stage markers are missing or invalid");
            }
            String stage = script.substring(begin + PERSISTED_STAGE_BEGIN.length(), end);
            return new ByteArrayResource(stage.getBytes(StandardCharsets.UTF_8),
                FIXTURE_SCRIPT + "#iam002-local-persisted");
        } catch (IOException exception) {
            throw new IllegalStateException("The local identity fixture could not be read", exception);
        }
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

    private void initializeOrSynchronizeReviewerCredential() {
        long userId = 101L;
        String username = "reviewer@platform.localhost";
        String storedPasswordHash = jdbc.queryForObject("""
            SELECT password_hash
              FROM iam_authentication_credential
             WHERE user_id = ? AND username = ? AND status = 'ACTIVE'
            """, String.class, userId, username);
        if (storedPasswordHash == null) {
            initializeCredential(userId, username);
            return;
        }
        if (passwordEncoder.matches(bootstrapPassword, storedPasswordHash)) {
            return;
        }

        int updated = jdbc.update("""
            UPDATE iam_authentication_credential
               SET password_hash = ?, updated_at = now(), row_version = row_version + 1
             WHERE user_id = ? AND username = ? AND status = 'ACTIVE'
               AND password_hash = ?
            """, passwordEncoder.encode(bootstrapPassword), userId, username, storedPasswordHash);
        int revoked = jdbc.update("""
            UPDATE iam_membership
               SET session_version = session_version + 1,
                   updated_at = now(), row_version = row_version + 1
             WHERE tenant_id = 1 AND user_id = ? AND status = 'ACTIVE'
            """, userId);
        if (updated != 1 || revoked != 1) {
            throw new IllegalStateException(
                "The local reviewer credential could not be synchronized atomically");
        }
    }

    private record FixtureReadiness(long identityRows,
                                    long grants,
                                    long dimensions,
                                    long targets,
                                    long menus,
                                    long roleMenus,
                                    long reviewerRows,
                                    long candidateRows,
                                    long boundMerchantRows,
                                    long isolatedIdentities,
                                    long isolatedRoleMenus) {
        boolean complete() {
            return identityRows == 1
                && grants == 38
                && dimensions == 38
                && targets == 0
                && menus == 37
                && roleMenus == 16
                && reviewerRows == 1
                && candidateRows == 1
                && boundMerchantRows == 1
                && isolatedIdentities == 2
                && isolatedRoleMenus == 4;
        }
    }
}
