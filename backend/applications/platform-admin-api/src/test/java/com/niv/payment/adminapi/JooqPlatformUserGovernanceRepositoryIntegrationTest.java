package com.niv.payment.adminapi;

import com.niv.payment.permission.domain.AccountDomain;
import com.niv.payment.permission.domain.AdministrationActor;
import com.niv.payment.permission.persistence.repository.JooqIdentityQueryRepository;
import com.niv.payment.permission.persistence.repository.JooqPlatformAdministrationDirectoryRepository;
import com.niv.payment.permission.persistence.repository.JooqPlatformUserGovernanceRepository;
import com.niv.payment.permission.port.PlatformAdministrationDirectoryPort;
import com.niv.payment.permission.port.PlatformUserGovernancePort;
import com.niv.payment.permission.service.IdentityAdministrationService;
import com.niv.payment.permission.service.IdentityModels;
import com.niv.payment.permission.service.RoleAssignmentPolicy;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;

import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_AUTHENTICATION_CREDENTIAL;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_AUDIT_EVENT;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_DEPARTMENT;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_GRANT_DIMENSION;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_MEMBERSHIP;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_MEMBERSHIP_ROLE;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_MENU;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_ROLE;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_ROLE_GRANT;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_TENANT;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_USER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
class JooqPlatformUserGovernanceRepositoryIntegrationTest {
    private static final String LOGIN_CAPABLE_HASH =
        "$2a$12$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";
    private static final long SOURCE_TENANT_ID = 9_410_000L;
    private static final long OTHER_PLATFORM_TENANT_ID = 9_420_000L;
    private static final long MERCHANT_TENANT_ID = 9_430_000L;
    private static final long OTHER_MERCHANT_TENANT_ID = 9_440_000L;
    private static final long SOURCE_USER_ID = 9_410_100L;
    private static final long SOURCE_MEMBERSHIP_ID = 9_410_101L;
    private static final long MERCHANT_USER_ID = 9_430_100L;
    private static final long MERCHANT_MEMBERSHIP_ID = 9_430_101L;
    private static final long OTHER_MERCHANT_MEMBERSHIP_ID = 9_440_101L;
    private static final long SOURCE_ROLE_ID = 9_410_200L;
    private static final long MERCHANT_ROLE_ID = 9_430_200L;
    private static final long MERCHANT_MENU_ID = 9_430_300L;
    private static final AdministrationActor SOURCE_ACTOR =
        new AdministrationActor(SOURCE_MEMBERSHIP_ID, SOURCE_USER_ID, 0L, 0L);

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse(
        "postgres:18.4-alpine@sha256:9a8afca54e7861fd90fab5fdf4c42477a6b1cb7d293595148e674e0a3181de15")
        .asCompatibleSubstituteFor("postgres"))
        .withDatabaseName("payment_platform")
        .withUsername("payment_dev")
        .withPassword("payment_dev");

    private static Connection connection;
    private static DSLContext dsl;
    private static JooqPlatformUserGovernanceRepository repository;
    private static JooqPlatformAdministrationDirectoryRepository directoryRepository;
    private static BCryptPasswordEncoder passwordEncoder;

    @BeforeAll
    static void migrate() throws Exception {
        PostgresFlywayTestSupport.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration")
            .load()
            .migrate();
        connection = DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        dsl = DSL.using(connection, SQLDialect.POSTGRES);
        passwordEncoder = new BCryptPasswordEncoder(10);
        repository = new JooqPlatformUserGovernanceRepository(
            dsl, () -> "platform-governance-test", () -> LOGIN_CAPABLE_HASH,
            () -> true, passwordEncoder::encode);
        directoryRepository = new JooqPlatformAdministrationDirectoryRepository(
            dsl, new JooqIdentityQueryRepository(dsl));
    }

    @BeforeEach
    void beginAndSeed() throws Exception {
        connection.setAutoCommit(false);
        seedTenant(SOURCE_TENANT_ID, "governance-platform", "PLATFORM", "PLATFORM");
        seedTenant(OTHER_PLATFORM_TENANT_ID, "governance-platform-other", "PLATFORM", "PLATFORM");
        seedTenant(MERCHANT_TENANT_ID, "governance-merchant", "DIRECT_MERCHANT", "MERCHANT");
        seedTenant(OTHER_MERCHANT_TENANT_ID, "governance-merchant-other", "DIRECT_MERCHANT", "MERCHANT");
        seedProtectedRole(SOURCE_TENANT_ID, SOURCE_ROLE_ID, "platform-protected", "PLATFORM", 3022L);
        seedProtectedRole(MERCHANT_TENANT_ID, MERCHANT_ROLE_ID,
            "merchant-protected", "DIRECT_MERCHANT", 3023L);
        seedUser(SOURCE_USER_ID, "admin@governance-platform.test", "Platform Controller",
            "platform remark", "PLATFORM", "local:platform");
        seedMembership(SOURCE_MEMBERSHIP_ID, SOURCE_TENANT_ID, SOURCE_USER_ID, "PLATFORM");
        assignRole(SOURCE_TENANT_ID, SOURCE_MEMBERSHIP_ID, SOURCE_ROLE_ID);
        seedUser(MERCHANT_USER_ID, "admin@governance-merchant.test", "Merchant Administrator",
            "merchant remark", "MERCHANT", "local:merchant");
        seedMembership(MERCHANT_MEMBERSHIP_ID, MERCHANT_TENANT_ID, MERCHANT_USER_ID, "MERCHANT");
        assignRole(MERCHANT_TENANT_ID, MERCHANT_MEMBERSHIP_ID, MERCHANT_ROLE_ID);
        seedMenu(MERCHANT_TENANT_ID, MERCHANT_MENU_ID, null, "MerchantRoot");
    }

    @AfterEach
    void rollback() throws Exception {
        connection.rollback();
        connection.setAutoCommit(true);
    }

    @AfterAll
    static void closeConnection() throws Exception {
        if (connection != null) connection.close();
    }

    @Test
    void platformDirectoryIsCurrentTenantScopedAndPreservesRemark() {
        long otherUserId = 9_420_100L;
        long otherMembershipId = 9_420_101L;
        seedUser(otherUserId, "admin@other-platform.test", "Other Platform User",
            "must remain invisible", "PLATFORM", "local:platform");
        seedMembership(otherMembershipId, OTHER_PLATFORM_TENANT_ID, otherUserId, "PLATFORM");

        var platformUsers = repository.findUsers(SOURCE_TENANT_ID, SOURCE_ACTOR,
            new PlatformUserGovernancePort.DirectoryQuery(
                AccountDomain.PLATFORM, null, null, null, null, null, 1, 20));

        assertEquals(1L, platformUsers.total());
        assertEquals(SOURCE_USER_ID, platformUsers.items().getFirst().id());
        assertEquals("platform remark", platformUsers.items().getFirst().remark());
        assertTrue(platformUsers.items().getFirst().systemAdministrator());
        assertThrows(SecurityException.class, () -> repository.findUsers(
            SOURCE_TENANT_ID, SOURCE_ACTOR,
            new PlatformUserGovernancePort.DirectoryQuery(
                AccountDomain.PLATFORM, OTHER_PLATFORM_TENANT_ID,
                null, null, null, null, 1, 20)));

        var merchantUsers = repository.findUsers(SOURCE_TENANT_ID, SOURCE_ACTOR,
            new PlatformUserGovernancePort.DirectoryQuery(
                AccountDomain.MERCHANT, null, null, null, null, null, 1, 20));
        assertEquals(1L, merchantUsers.total());
        assertEquals("merchant remark", merchantUsers.items().getFirst().remark());
    }

    @Test
    void platformDirectoryFiltersByMembershipDepartmentAndRejectsCrossDomainDepartmentFilters() {
        long secondDepartmentId = SOURCE_TENANT_ID + 11;
        long secondUserId = 9_410_110L;
        long secondMembershipId = 9_410_111L;
        dsl.insertInto(IAM_DEPARTMENT,
                IAM_DEPARTMENT.ID, IAM_DEPARTMENT.TENANT_ID, IAM_DEPARTMENT.DEPARTMENT_CODE,
                IAM_DEPARTMENT.DEPARTMENT_NAME, IAM_DEPARTMENT.STATUS,
                IAM_DEPARTMENT.SYSTEM_MANAGED)
            .values(secondDepartmentId, SOURCE_TENANT_ID, "governance-platform-second",
                "governance platform second", "ACTIVE", false)
            .execute();
        seedUser(secondUserId, "second@governance-platform.test", "Second Platform User",
            null, "PLATFORM", "local:platform");
        dsl.insertInto(IAM_MEMBERSHIP,
                IAM_MEMBERSHIP.ID, IAM_MEMBERSHIP.TENANT_ID, IAM_MEMBERSHIP.USER_ID,
                IAM_MEMBERSHIP.DEPARTMENT_ID, IAM_MEMBERSHIP.STATUS,
                IAM_MEMBERSHIP.ACCOUNT_DOMAIN)
            .values(secondMembershipId, SOURCE_TENANT_ID, secondUserId,
                secondDepartmentId, "ACTIVE", "PLATFORM")
            .execute();

        var users = repository.findUsers(SOURCE_TENANT_ID, SOURCE_ACTOR,
            new PlatformUserGovernancePort.DirectoryQuery(
                AccountDomain.PLATFORM, null, secondDepartmentId,
                null, null, null, 1, 20));

        assertEquals(1L, users.total());
        assertEquals(secondUserId, users.items().getFirst().id());
        assertThrows(SecurityException.class, () -> repository.findUsers(
            SOURCE_TENANT_ID, SOURCE_ACTOR,
            new PlatformUserGovernancePort.DirectoryQuery(
                AccountDomain.MERCHANT, MERCHANT_TENANT_ID, MERCHANT_TENANT_ID + 10,
                null, null, null, 1, 20)));
    }

    @Test
    void platformSystemAdministratorReadsOnlyAnActiveExactTenantRoleDirectory() {
        var platformRoles = directoryRepository.findRoles(
            SOURCE_TENANT_ID, SOURCE_ACTOR,
            new PlatformAdministrationDirectoryPort.RoleDirectoryQuery(
                AccountDomain.PLATFORM, SOURCE_TENANT_ID,
                new IdentityModels.RoleQuery(
                    "platform-protected", null, 1, null, null, null, 1, 20)));
        assertEquals(1L, platformRoles.total());
        assertEquals(SOURCE_TENANT_ID, platformRoles.items().getFirst().scope().tenantId());
        assertEquals(PlatformAdministrationDirectoryPort.ManagementMode.SAME_TENANT,
            platformRoles.items().getFirst().scope().managementMode());

        var merchantRoles = directoryRepository.findRoles(
            SOURCE_TENANT_ID, SOURCE_ACTOR,
            new PlatformAdministrationDirectoryPort.RoleDirectoryQuery(
                AccountDomain.MERCHANT, MERCHANT_TENANT_ID,
                new IdentityModels.RoleQuery(
                    "merchant-protected", null, 1, null, null, null, 1, 20)));
        assertEquals(1L, merchantRoles.total());
        assertEquals(MERCHANT_TENANT_ID, merchantRoles.items().getFirst().scope().tenantId());
        assertEquals(AccountDomain.MERCHANT,
            merchantRoles.items().getFirst().scope().accountDomain());
        assertEquals(PlatformAdministrationDirectoryPort.ManagementMode.READ_ONLY,
            merchantRoles.items().getFirst().scope().managementMode());

        assertThrows(SecurityException.class, () -> directoryRepository.findRoles(
            SOURCE_TENANT_ID, SOURCE_ACTOR,
            new PlatformAdministrationDirectoryPort.RoleDirectoryQuery(
                AccountDomain.PLATFORM, OTHER_PLATFORM_TENANT_ID,
                new IdentityModels.RoleQuery(
                    null, null, null, null, null, null, 1, 20))));
        assertThrows(IdentityAdministrationService.ResourceNotFoundException.class,
            () -> directoryRepository.findRoles(
                SOURCE_TENANT_ID, SOURCE_ACTOR,
                new PlatformAdministrationDirectoryPort.RoleDirectoryQuery(
                    AccountDomain.AGENT, MERCHANT_TENANT_ID,
                    new IdentityModels.RoleQuery(
                        null, null, null, null, null, null, 1, 20))));
    }

    @Test
    void menuDirectoryReturnsOneTargetTenantTreeAndFailsClosedForDisabledTenant() {
        var menus = directoryRepository.findMenus(
            SOURCE_TENANT_ID, SOURCE_ACTOR,
            new PlatformAdministrationDirectoryPort.MenuDirectoryQuery(
                AccountDomain.MERCHANT, MERCHANT_TENANT_ID, false));

        assertEquals(1, menus.size());
        assertEquals(MERCHANT_MENU_ID, menus.getFirst().menu().id());
        assertEquals(MERCHANT_TENANT_ID, menus.getFirst().scope().tenantId());
        assertEquals(PlatformAdministrationDirectoryPort.ManagementMode.READ_ONLY,
            menus.getFirst().scope().managementMode());

        dsl.update(IAM_TENANT).set(IAM_TENANT.STATUS, "DISABLED")
            .where(IAM_TENANT.ID.eq(MERCHANT_TENANT_ID)).execute();
        assertThrows(IdentityAdministrationService.ResourceNotFoundException.class,
            () -> directoryRepository.findMenus(
                SOURCE_TENANT_ID, SOURCE_ACTOR,
                new PlatformAdministrationDirectoryPort.MenuDirectoryQuery(
                    AccountDomain.MERCHANT, MERCHANT_TENANT_ID, false)));
    }

    @Test
    void directoryRequiresTheProtectedSourceSystemRole() {
        dsl.deleteFrom(IAM_MEMBERSHIP_ROLE)
            .where(IAM_MEMBERSHIP_ROLE.TENANT_ID.eq(SOURCE_TENANT_ID)
                .and(IAM_MEMBERSHIP_ROLE.MEMBERSHIP_ID.eq(SOURCE_MEMBERSHIP_ID)))
            .execute();

        assertThrows(SecurityException.class, () -> directoryRepository.findMenus(
            SOURCE_TENANT_ID, SOURCE_ACTOR,
            new PlatformAdministrationDirectoryPort.MenuDirectoryQuery(
                AccountDomain.MERCHANT, MERCHANT_TENANT_ID, false)));
    }

    @Test
    void emailChangeRevokesEveryNonTerminatedMembershipSession() {
        seedMembership(OTHER_MERCHANT_MEMBERSHIP_ID, OTHER_MERCHANT_TENANT_ID,
            MERCHANT_USER_ID, "MERCHANT");

        repository.updateTenantAdministrator(SOURCE_TENANT_ID, SOURCE_ACTOR, MERCHANT_USER_ID,
            new PlatformUserGovernancePort.UpdateAdministratorCommand(
                AccountDomain.MERCHANT, MERCHANT_TENANT_ID,
                "renamed@governance-merchant.test", "Renamed Merchant Administrator",
                1, 0L, 0L, 0L));

        assertEquals(1L, sessionVersion(MERCHANT_MEMBERSHIP_ID));
        assertEquals(1L, sessionVersion(OTHER_MERCHANT_MEMBERSHIP_ID));
        assertEquals("renamed@governance-merchant.test",
            dsl.select(IAM_AUTHENTICATION_CREDENTIAL.USERNAME)
                .from(IAM_AUTHENTICATION_CREDENTIAL)
                .where(IAM_AUTHENTICATION_CREDENTIAL.USER_ID.eq(MERCHANT_USER_ID))
                .fetchSingle(IAM_AUTHENTICATION_CREDENTIAL.USERNAME));
    }

    @Test
    void platformCanResetAnyBoundLocalUserAndRevokeEveryMembershipSession() {
        String password = localTestPassword();
        seedMembership(OTHER_MERCHANT_MEMBERSHIP_ID, OTHER_MERCHANT_TENANT_ID,
            MERCHANT_USER_ID, "MERCHANT");
        dsl.deleteFrom(IAM_MEMBERSHIP_ROLE)
            .where(IAM_MEMBERSHIP_ROLE.MEMBERSHIP_ID.eq(MERCHANT_MEMBERSHIP_ID))
            .execute();

        var result = repository.resetUserPassword(
            SOURCE_TENANT_ID, SOURCE_ACTOR, MERCHANT_USER_ID,
            new PlatformUserGovernancePort.PasswordResetCommand(
                AccountDomain.MERCHANT, MERCHANT_TENANT_ID, 0, password));

        String storedHash = dsl.select(IAM_AUTHENTICATION_CREDENTIAL.PASSWORD_HASH)
            .from(IAM_AUTHENTICATION_CREDENTIAL)
            .where(IAM_AUTHENTICATION_CREDENTIAL.USER_ID.eq(MERCHANT_USER_ID))
            .fetchSingle(IAM_AUTHENTICATION_CREDENTIAL.PASSWORD_HASH);
        assertTrue(passwordEncoder.matches(password, storedHash));
        assertFalse(password.equals(storedHash));
        assertEquals(1L, result.credentialVersion());
        assertEquals(1L, sessionVersion(MERCHANT_MEMBERSHIP_ID));
        assertEquals(1L, sessionVersion(OTHER_MERCHANT_MEMBERSHIP_ID));
        String auditEvidence = dsl.select(IAM_AUDIT_EVENT.AFTER_VALUE)
            .from(IAM_AUDIT_EVENT)
            .where(IAM_AUDIT_EVENT.ACTION_CODE.eq("RESET_LOCAL_PASSWORD"))
            .fetchSingle(IAM_AUDIT_EVENT.AFTER_VALUE).data();
        assertFalse(auditEvidence.contains(password));

        assertThrows(IdentityAdministrationService.OptimisticLockException.class,
            () -> repository.resetUserPassword(
                SOURCE_TENANT_ID, SOURCE_ACTOR, MERCHANT_USER_ID,
                new PlatformUserGovernancePort.PasswordResetCommand(
                    AccountDomain.MERCHANT, MERCHANT_TENANT_ID, 0, password)));
    }

    @Test
    void crossDomainResetFailsClosedForExternalInactiveAndDisabledLocalCapability() {
        String originalHash = dsl.select(IAM_AUTHENTICATION_CREDENTIAL.PASSWORD_HASH)
            .from(IAM_AUTHENTICATION_CREDENTIAL)
            .where(IAM_AUTHENTICATION_CREDENTIAL.USER_ID.eq(MERCHANT_USER_ID))
            .fetchSingle(IAM_AUTHENTICATION_CREDENTIAL.PASSWORD_HASH);
        var command = new PlatformUserGovernancePort.PasswordResetCommand(
            AccountDomain.MERCHANT, MERCHANT_TENANT_ID, 0, localTestPassword());

        dsl.update(IAM_USER).set(IAM_USER.IDP_ISSUER, "https://idp.example.test")
            .where(IAM_USER.ID.eq(MERCHANT_USER_ID)).execute();
        assertThrows(IdentityAdministrationService.DataConflictException.class,
            () -> repository.resetUserPassword(
                SOURCE_TENANT_ID, SOURCE_ACTOR, MERCHANT_USER_ID, command));
        dsl.update(IAM_USER).set(IAM_USER.IDP_ISSUER, "local:merchant")
            .set(IAM_USER.STATUS, "DISABLED")
            .where(IAM_USER.ID.eq(MERCHANT_USER_ID)).execute();
        assertThrows(IdentityAdministrationService.DataConflictException.class,
            () -> repository.resetUserPassword(
                SOURCE_TENANT_ID, SOURCE_ACTOR, MERCHANT_USER_ID, command));

        var disabled = new JooqPlatformUserGovernanceRepository(
            dsl, () -> "platform-governance-test", () -> LOGIN_CAPABLE_HASH,
            () -> false, passwordEncoder::encode);
        assertThrows(IdentityAdministrationService.DataConflictException.class,
            () -> disabled.resetUserPassword(
                SOURCE_TENANT_ID, SOURCE_ACTOR, MERCHANT_USER_ID, command));
        assertEquals(originalHash, dsl.select(IAM_AUTHENTICATION_CREDENTIAL.PASSWORD_HASH)
            .from(IAM_AUTHENTICATION_CREDENTIAL)
            .where(IAM_AUTHENTICATION_CREDENTIAL.USER_ID.eq(MERCHANT_USER_ID))
            .fetchSingle(IAM_AUTHENTICATION_CREDENTIAL.PASSWORD_HASH));
        assertEquals(0L, sessionVersion(MERCHANT_MEMBERSHIP_ID));
    }

    @Test
    void onlyLoginCapableProtectedRoleCanReplaceOrBeManagedAsTenantAdministrator() {
        long fakeRoleId = 9_430_210L;
        long fakeUserId = 9_430_300L;
        long fakeMembershipId = 9_430_301L;
        dsl.insertInto(IAM_ROLE,
                IAM_ROLE.ID, IAM_ROLE.TENANT_ID, IAM_ROLE.ROLE_CODE, IAM_ROLE.ROLE_NAME,
                IAM_ROLE.APPLICABLE_TENANT_TYPE, IAM_ROLE.ASSIGNABLE, IAM_ROLE.SYSTEM_ROLE,
                IAM_ROLE.STATUS)
            .values(fakeRoleId, MERCHANT_TENANT_ID, "fake-system", "Fake System Role",
                "DIRECT_MERCHANT", true, true, "ACTIVE")
            .execute();
        seedUser(fakeUserId, "fake-admin@governance-merchant.test", "Fake Administrator",
            null, "MERCHANT", "local:merchant");
        seedMembership(fakeMembershipId, MERCHANT_TENANT_ID, fakeUserId, "MERCHANT");
        assignRole(MERCHANT_TENANT_ID, fakeMembershipId, fakeRoleId);

        var fakeDirectoryUser = repository.findUsers(SOURCE_TENANT_ID, SOURCE_ACTOR,
            new PlatformUserGovernancePort.DirectoryQuery(
                AccountDomain.MERCHANT, MERCHANT_TENANT_ID,
                null, "fake-admin", null, null, 1, 20)).items().getFirst();
        assertFalse(fakeDirectoryUser.systemAdministrator());
        assertThrows(IdentityAdministrationService.ResourceNotFoundException.class,
            () -> repository.updateTenantAdministrator(
                SOURCE_TENANT_ID, SOURCE_ACTOR, fakeUserId,
                new PlatformUserGovernancePort.UpdateAdministratorCommand(
                    AccountDomain.MERCHANT, MERCHANT_TENANT_ID,
                    "fake-admin@governance-merchant.test", "Fake Administrator",
                    1, 0L, 0L, 0L)));
        assertThrows(RoleAssignmentPolicy.LastAdministratorException.class,
            () -> repository.updateTenantAdministrator(
                SOURCE_TENANT_ID, SOURCE_ACTOR, MERCHANT_USER_ID,
                new PlatformUserGovernancePort.UpdateAdministratorCommand(
                    AccountDomain.MERCHANT, MERCHANT_TENANT_ID,
                    "admin@governance-merchant.test", "Merchant Administrator",
                    0, 0L, 0L, 0L)));

        assignRole(MERCHANT_TENANT_ID, fakeMembershipId, MERCHANT_ROLE_ID);
        repository.updateTenantAdministrator(
            SOURCE_TENANT_ID, SOURCE_ACTOR, MERCHANT_USER_ID,
            new PlatformUserGovernancePort.UpdateAdministratorCommand(
                AccountDomain.MERCHANT, MERCHANT_TENANT_ID,
                "admin@governance-merchant.test", "Merchant Administrator",
                0, 0L, 0L, 0L));
        assertEquals("DISABLED", dsl.select(IAM_MEMBERSHIP.STATUS).from(IAM_MEMBERSHIP)
            .where(IAM_MEMBERSHIP.ID.eq(MERCHANT_MEMBERSHIP_ID))
            .fetchSingle(IAM_MEMBERSHIP.STATUS));
    }

    private static void seedTenant(long tenantId, String code, String tenantType, String domain) {
        dsl.insertInto(IAM_TENANT,
                IAM_TENANT.ID, IAM_TENANT.TENANT_CODE, IAM_TENANT.TENANT_NAME,
                IAM_TENANT.TENANT_TYPE, IAM_TENANT.STATUS, IAM_TENANT.ACCOUNT_DOMAIN)
            .values(tenantId, code, code, tenantType, "ACTIVE", domain)
            .execute();
        dsl.insertInto(IAM_DEPARTMENT,
                IAM_DEPARTMENT.ID, IAM_DEPARTMENT.TENANT_ID, IAM_DEPARTMENT.DEPARTMENT_CODE,
                IAM_DEPARTMENT.DEPARTMENT_NAME, IAM_DEPARTMENT.STATUS,
                IAM_DEPARTMENT.SYSTEM_MANAGED)
            .values(tenantId + 10, tenantId, code + "-root", code + " root", "ACTIVE", true)
            .execute();
    }

    private static void seedProtectedRole(long tenantId, long roleId, String roleCode,
                                          String tenantType, long entryPermissionId) {
        dsl.insertInto(IAM_ROLE,
                IAM_ROLE.ID, IAM_ROLE.TENANT_ID, IAM_ROLE.ROLE_CODE, IAM_ROLE.ROLE_NAME,
                IAM_ROLE.APPLICABLE_TENANT_TYPE, IAM_ROLE.ASSIGNABLE, IAM_ROLE.SYSTEM_ROLE,
                IAM_ROLE.STATUS)
            .values(roleId, tenantId, roleCode, roleCode, tenantType, false, true, "ACTIVE")
            .execute();
        long grantId = roleId + 1;
        dsl.insertInto(IAM_ROLE_GRANT,
                IAM_ROLE_GRANT.ID, IAM_ROLE_GRANT.TENANT_ID, IAM_ROLE_GRANT.ROLE_ID,
                IAM_ROLE_GRANT.PERMISSION_ID, IAM_ROLE_GRANT.GRANT_KEY, IAM_ROLE_GRANT.STATUS)
            .values(grantId, tenantId, roleId, entryPermissionId,
                "system-backoffice-access", "ACTIVE")
            .execute();
        dsl.insertInto(IAM_GRANT_DIMENSION,
                IAM_GRANT_DIMENSION.ID, IAM_GRANT_DIMENSION.GRANT_ID,
                IAM_GRANT_DIMENSION.DIMENSION_CODE, IAM_GRANT_DIMENSION.SCOPE_MODE)
            .values(roleId + 2, grantId, "TENANT", "TENANT_ALL")
            .execute();
    }

    private static void seedUser(long userId, String email, String name, String remark,
                                 String domain, String issuer) {
        dsl.insertInto(IAM_USER,
                IAM_USER.ID, IAM_USER.IDP_ISSUER, IAM_USER.IDP_SUBJECT,
                IAM_USER.DISPLAY_NAME, IAM_USER.STATUS, IAM_USER.REMARK,
                IAM_USER.ACCOUNT_DOMAIN)
            .values(userId, issuer, email, name, "ACTIVE", remark, domain)
            .execute();
        dsl.insertInto(IAM_AUTHENTICATION_CREDENTIAL,
                IAM_AUTHENTICATION_CREDENTIAL.USER_ID, IAM_AUTHENTICATION_CREDENTIAL.USERNAME,
                IAM_AUTHENTICATION_CREDENTIAL.PASSWORD_HASH, IAM_AUTHENTICATION_CREDENTIAL.STATUS,
                IAM_AUTHENTICATION_CREDENTIAL.ACCOUNT_DOMAIN)
            .values(userId, email, LOGIN_CAPABLE_HASH, "ACTIVE", domain)
            .execute();
    }

    private static void seedMembership(long membershipId, long tenantId, long userId,
                                       String domain) {
        dsl.insertInto(IAM_MEMBERSHIP,
                IAM_MEMBERSHIP.ID, IAM_MEMBERSHIP.TENANT_ID, IAM_MEMBERSHIP.USER_ID,
                IAM_MEMBERSHIP.DEPARTMENT_ID, IAM_MEMBERSHIP.STATUS,
                IAM_MEMBERSHIP.ACCOUNT_DOMAIN)
            .values(membershipId, tenantId, userId, tenantId + 10, "ACTIVE", domain)
            .execute();
    }

    private static void assignRole(long tenantId, long membershipId, long roleId) {
        dsl.insertInto(IAM_MEMBERSHIP_ROLE,
                IAM_MEMBERSHIP_ROLE.TENANT_ID, IAM_MEMBERSHIP_ROLE.MEMBERSHIP_ID,
                IAM_MEMBERSHIP_ROLE.ROLE_ID)
            .values(tenantId, membershipId, roleId)
            .execute();
    }

    private static void seedMenu(long tenantId, long menuId, Long parentId, String name) {
        dsl.insertInto(IAM_MENU,
                IAM_MENU.ID, IAM_MENU.TENANT_ID, IAM_MENU.PARENT_ID,
                IAM_MENU.MENU_TYPE, IAM_MENU.MENU_NAME, IAM_MENU.ROUTE_NAME,
                IAM_MENU.ROUTE_PATH, IAM_MENU.COMPONENT_PATH, IAM_MENU.SORT_ORDER,
                IAM_MENU.STATUS, IAM_MENU.META_JSON)
            .values(menuId, tenantId, parentId, "PAGE", name, name,
                "/" + name.toLowerCase(), "/test/index", 1, "ACTIVE",
                org.jooq.JSONB.valueOf("{}"))
            .execute();
    }

    private static long sessionVersion(long membershipId) {
        return dsl.select(IAM_MEMBERSHIP.SESSION_VERSION).from(IAM_MEMBERSHIP)
            .where(IAM_MEMBERSHIP.ID.eq(membershipId))
            .fetchSingle(IAM_MEMBERSHIP.SESSION_VERSION);
    }

    private static String localTestPassword() {
        return String.join("", "Abcd1234", "Efgh!!!!");
    }
}
