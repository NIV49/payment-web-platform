package com.niv.payment.adminapi;

import com.niv.payment.permission.domain.AdministrationActor;
import com.niv.payment.permission.persistence.repository.JooqIdentityQueryRepository;
import com.niv.payment.permission.persistence.repository.JooqUserAdministrationRepository;
import com.niv.payment.permission.service.IdentityModels;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.DriverManager;
import java.util.List;

import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_AUTHENTICATION_CREDENTIAL;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_DEPARTMENT;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_MEMBERSHIP;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_MEMBERSHIP_ROLE;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_ROLE;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_TENANT;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_USER;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class DomainAwareUsernameRepositoryIntegrationTest {
    private static final String SUPPORTED_DUMMY_HASH =
        "$2a$12$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";
    private static final long PLATFORM_TENANT_ID = 23_001L;
    private static final long MERCHANT_TENANT_ID = 23_002L;
    private static final long DEPARTMENT_ID = 23_003L;
    private static final long ACTOR_USER_ID = 23_004L;
    private static final long ACTOR_MEMBERSHIP_ID = 23_005L;
    private static final long SYSTEM_ROLE_ID = 23_006L;
    private static final long TARGET_USER_ID = 23_007L;
    private static final long TARGET_MEMBERSHIP_ID = 23_008L;
    private static final long MERCHANT_USER_ID = 23_009L;

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse(
        "postgres:18.4-alpine@sha256:9a8afca54e7861fd90fab5fdf4c42477a6b1cb7d293595148e674e0a3181de15")
        .asCompatibleSubstituteFor("postgres"))
        .withDatabaseName("payment_platform")
        .withUsername("payment_dev")
        .withPassword("payment_dev");

    @Test
    void platformRenameIgnoresCredentialUsernameOwnedByAnotherAccountDomain() throws Exception {
        PostgresFlywayTestSupport.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration")
            .load()
            .migrate();

        try (var connection = DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            var dsl = DSL.using(connection, SQLDialect.POSTGRES);
            dsl.alterTable(IAM_AUTHENTICATION_CREDENTIAL)
                .dropConstraint("uk_iam_authentication_username")
                .execute();
            dsl.insertInto(IAM_TENANT,
                    IAM_TENANT.ID, IAM_TENANT.TENANT_CODE, IAM_TENANT.TENANT_NAME,
                    IAM_TENANT.TENANT_TYPE, IAM_TENANT.STATUS, IAM_TENANT.ACCOUNT_DOMAIN)
                .values(PLATFORM_TENANT_ID, "platform-23001", "Platform 23001",
                    "PLATFORM", "ACTIVE", "PLATFORM")
                .values(MERCHANT_TENANT_ID, "merchant-23002", "Merchant 23002",
                    "DIRECT_MERCHANT", "ACTIVE", "MERCHANT")
                .execute();
            dsl.insertInto(IAM_DEPARTMENT,
                    IAM_DEPARTMENT.ID, IAM_DEPARTMENT.TENANT_ID,
                    IAM_DEPARTMENT.DEPARTMENT_CODE, IAM_DEPARTMENT.DEPARTMENT_NAME,
                    IAM_DEPARTMENT.STATUS)
                .values(DEPARTMENT_ID, PLATFORM_TENANT_ID, "root", "Root", "ACTIVE")
                .execute();
            insertUser(dsl, ACTOR_USER_ID, "platform-actor", "Platform Actor", "PLATFORM", "local");
            insertUser(dsl, TARGET_USER_ID, "platform-target", "Platform Target", "PLATFORM", "local");
            insertUser(dsl, MERCHANT_USER_ID, "shared-login", "Merchant User", "MERCHANT",
                "https://merchant-id.example.test/realms/merchant");
            dsl.update(IAM_AUTHENTICATION_CREDENTIAL)
                .set(IAM_AUTHENTICATION_CREDENTIAL.PASSWORD_HASH, SUPPORTED_DUMMY_HASH)
                .set(IAM_AUTHENTICATION_CREDENTIAL.STATUS, "ACTIVE")
                .where(IAM_AUTHENTICATION_CREDENTIAL.USER_ID.eq(ACTOR_USER_ID))
                .execute();
            dsl.insertInto(IAM_MEMBERSHIP,
                    IAM_MEMBERSHIP.ID, IAM_MEMBERSHIP.TENANT_ID, IAM_MEMBERSHIP.USER_ID,
                    IAM_MEMBERSHIP.DEPARTMENT_ID, IAM_MEMBERSHIP.STATUS,
                    IAM_MEMBERSHIP.ACCOUNT_DOMAIN)
                .values(ACTOR_MEMBERSHIP_ID, PLATFORM_TENANT_ID, ACTOR_USER_ID,
                    DEPARTMENT_ID, "ACTIVE", "PLATFORM")
                .values(TARGET_MEMBERSHIP_ID, PLATFORM_TENANT_ID, TARGET_USER_ID,
                    DEPARTMENT_ID, "ACTIVE", "PLATFORM")
                .execute();
            dsl.insertInto(IAM_ROLE,
                    IAM_ROLE.ID, IAM_ROLE.TENANT_ID, IAM_ROLE.ROLE_CODE, IAM_ROLE.ROLE_NAME,
                    IAM_ROLE.APPLICABLE_TENANT_TYPE, IAM_ROLE.ASSIGNABLE,
                    IAM_ROLE.SYSTEM_ROLE, IAM_ROLE.STATUS)
                .values(SYSTEM_ROLE_ID, PLATFORM_TENANT_ID, "system-admin", "System Admin",
                    "PLATFORM", false, true, "ACTIVE")
                .execute();
            dsl.insertInto(IAM_MEMBERSHIP_ROLE,
                    IAM_MEMBERSHIP_ROLE.TENANT_ID, IAM_MEMBERSHIP_ROLE.MEMBERSHIP_ID,
                    IAM_MEMBERSHIP_ROLE.ROLE_ID, IAM_MEMBERSHIP_ROLE.ASSIGNED_BY)
                .values(PLATFORM_TENANT_ID, ACTOR_MEMBERSHIP_ID,
                    SYSTEM_ROLE_ID, ACTOR_MEMBERSHIP_ID)
                .execute();

            var repository = new JooqUserAdministrationRepository(
                dsl, new JooqIdentityQueryRepository(dsl), () -> "domain-username-test");
            repository.updateUser(
                PLATFORM_TENANT_ID,
                new AdministrationActor(ACTOR_MEMBERSHIP_ID, ACTOR_USER_ID, 0L, 0L),
                TARGET_USER_ID,
                new IdentityModels.MembershipUpdateCommand(
                    "shared-login", "Platform Target", DEPARTMENT_ID, List.of(), 1,
                    0L, 0L, 0L, null));

            assertThat(dsl.select(IAM_AUTHENTICATION_CREDENTIAL.USERNAME)
                .from(IAM_AUTHENTICATION_CREDENTIAL)
                .where(IAM_AUTHENTICATION_CREDENTIAL.USER_ID.eq(TARGET_USER_ID))
                .fetchSingle(IAM_AUTHENTICATION_CREDENTIAL.USERNAME))
                .isEqualTo("shared-login");
        }
    }

    private static void insertUser(org.jooq.DSLContext dsl, long userId, String username,
                                   String displayName, String accountDomain, String issuer) {
        dsl.insertInto(IAM_USER,
                IAM_USER.ID, IAM_USER.IDP_ISSUER, IAM_USER.IDP_SUBJECT,
                IAM_USER.DISPLAY_NAME, IAM_USER.STATUS, IAM_USER.ACCOUNT_DOMAIN)
            .values(userId, issuer, username + "-subject", displayName, "ACTIVE", accountDomain)
            .execute();
        dsl.insertInto(IAM_AUTHENTICATION_CREDENTIAL,
                IAM_AUTHENTICATION_CREDENTIAL.USER_ID, IAM_AUTHENTICATION_CREDENTIAL.USERNAME,
                IAM_AUTHENTICATION_CREDENTIAL.STATUS, IAM_AUTHENTICATION_CREDENTIAL.ACCOUNT_DOMAIN)
            .values(userId, username, "DISABLED", accountDomain)
            .execute();
    }
}
