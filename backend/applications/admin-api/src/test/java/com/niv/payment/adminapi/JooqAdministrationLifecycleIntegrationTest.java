package com.niv.payment.adminapi;

import com.niv.payment.permission.domain.AdministrationActor;
import com.niv.payment.permission.persistence.repository.JooqDepartmentAdministrationRepository;
import com.niv.payment.permission.persistence.repository.JooqIdentityQueryRepository;
import com.niv.payment.permission.persistence.repository.JooqMenuAdministrationRepository;
import com.niv.payment.permission.persistence.repository.JooqUserAdministrationRepository;
import com.niv.payment.permission.service.IdentityAdministrationService;
import com.niv.payment.permission.service.IdentityModels;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;

import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_AUTHENTICATION_CREDENTIAL;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_DEPARTMENT;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_GRANT_DIMENSION;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_GRANT_TARGET;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_MEMBERSHIP;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_MEMBERSHIP_ROLE;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_MENU;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_ROLE;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_ROLE_GRANT;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_TENANT;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_USER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
class JooqAdministrationLifecycleIntegrationTest {
    private static final String SUPPORTED_DUMMY_HASH =
        "$2a$12$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";
    private static final long TENANT_ID = 9_310_000L;
    private static final long OTHER_TENANT_ID = 9_320_000L;
    private static final long TERMINATED_TENANT_ID = 9_330_000L;
    private static final long ROOT_DEPARTMENT_ID = 9_310_010L;
    private static final long OTHER_ROOT_DEPARTMENT_ID = 9_320_010L;
    private static final long TERMINATED_ROOT_DEPARTMENT_ID = 9_330_010L;
    private static final long ACTOR_USER_ID = 9_310_100L;
    private static final long ACTOR_MEMBERSHIP_ID = 9_310_101L;
    private static final long PLAIN_ACTOR_USER_ID = 9_310_110L;
    private static final long PLAIN_ACTOR_MEMBERSHIP_ID = 9_310_111L;
    private static final long SYSTEM_ROLE_ID = 9_310_200L;
    private static final long TARGET_USER_ID = 9_310_300L;
    private static final long TARGET_MEMBERSHIP_ID = 9_310_301L;
    private static final long OTHER_MEMBERSHIP_ID = 9_320_301L;
    private static final long TERMINATED_MEMBERSHIP_ID = 9_330_301L;
    private static final long CONFLICT_USER_ID = 9_310_400L;
    private static final AdministrationActor SYSTEM_ACTOR =
        new AdministrationActor(ACTOR_MEMBERSHIP_ID, ACTOR_USER_ID, 0L, 0L);
    private static final AdministrationActor PLAIN_ACTOR =
        new AdministrationActor(PLAIN_ACTOR_MEMBERSHIP_ID, PLAIN_ACTOR_USER_ID, 0L, 0L);

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse(
        "postgres:18.4-alpine@sha256:9a8afca54e7861fd90fab5fdf4c42477a6b1cb7d293595148e674e0a3181de15")
        .asCompatibleSubstituteFor("postgres"))
        .withDatabaseName("payment_platform")
        .withUsername("payment_dev")
        .withPassword("payment_dev");

    private static Connection connection;
    private static DSLContext dsl;
    private static JooqIdentityQueryRepository queries;
    private static JooqDepartmentAdministrationRepository departments;
    private static JooqMenuAdministrationRepository menus;
    private static JooqUserAdministrationRepository users;

    @BeforeAll
    static void migrateAndSeed() throws Exception {
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration")
            .load()
            .migrate();
        connection = DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        dsl = DSL.using(connection, SQLDialect.POSTGRES);

        seedTenant(TENANT_ID, "lifecycle-primary", ROOT_DEPARTMENT_ID);
        seedTenant(OTHER_TENANT_ID, "lifecycle-secondary", OTHER_ROOT_DEPARTMENT_ID);
        seedTenant(TERMINATED_TENANT_ID, "lifecycle-terminated", TERMINATED_ROOT_DEPARTMENT_ID);
        seedUser(ACTOR_USER_ID, "lifecycle-system-actor", "System Actor");
        seedMembership(ACTOR_MEMBERSHIP_ID, TENANT_ID, ACTOR_USER_ID, ROOT_DEPARTMENT_ID, "ACTIVE");
        seedUser(PLAIN_ACTOR_USER_ID, "lifecycle-plain-actor", "Plain Actor");
        seedMembership(PLAIN_ACTOR_MEMBERSHIP_ID, TENANT_ID, PLAIN_ACTOR_USER_ID, ROOT_DEPARTMENT_ID, "ACTIVE");
        dsl.insertInto(IAM_ROLE,
                IAM_ROLE.ID, IAM_ROLE.TENANT_ID, IAM_ROLE.ROLE_CODE, IAM_ROLE.ROLE_NAME,
                IAM_ROLE.APPLICABLE_TENANT_TYPE, IAM_ROLE.ASSIGNABLE, IAM_ROLE.SYSTEM_ROLE,
                IAM_ROLE.STATUS)
            .values(SYSTEM_ROLE_ID, TENANT_ID, "lifecycle-system-role", "Lifecycle System Role",
                "PLATFORM", false, true, "ACTIVE")
            .execute();
        dsl.insertInto(IAM_MEMBERSHIP_ROLE,
                IAM_MEMBERSHIP_ROLE.TENANT_ID, IAM_MEMBERSHIP_ROLE.MEMBERSHIP_ID,
                IAM_MEMBERSHIP_ROLE.ROLE_ID, IAM_MEMBERSHIP_ROLE.ASSIGNED_BY)
            .values(TENANT_ID, ACTOR_MEMBERSHIP_ID, SYSTEM_ROLE_ID, ACTOR_MEMBERSHIP_ID)
            .execute();
        seedEditableTarget();

        queries = new JooqIdentityQueryRepository(dsl);
        departments = new JooqDepartmentAdministrationRepository(dsl, queries, () -> "department-lifecycle-test");
        menus = new JooqMenuAdministrationRepository(dsl, queries, () -> "menu-lifecycle-test");
        users = new JooqUserAdministrationRepository(dsl, queries, () -> "user-lifecycle-test");
    }

    @AfterAll
    static void closeConnection() throws Exception {
        if (connection != null) {
            connection.close();
        }
    }

    @Test
    void managementAndSelectableQueriesExcludeTheCorrectDepartmentAndMenuRows() {
        long terminatedUserId = 9_310_700L;
        seedUser(terminatedUserId, "lifecycle-terminated-user", "Terminated User");
        seedMembership(9_310_701L, TENANT_ID, terminatedUserId, ROOT_DEPARTMENT_ID, "TERMINATED");
        assertFalse(queries.findUsers(TENANT_ID,
                new IdentityModels.UserQuery(null, null, terminatedUserId, null, null,
                    null, null, 1, 20))
            .items().stream().anyMatch(user -> user.id() == terminatedUserId));

        long activeDepartment = departments.createDepartment(TENANT_ID, SYSTEM_ACTOR,
            department(null, "Lifecycle Active Department", 1));
        long disabledDepartment = departments.createDepartment(TENANT_ID, SYSTEM_ACTOR,
            department(null, "Lifecycle Disabled Department", 0));
        long deletedDepartment = departments.createDepartment(TENANT_ID, SYSTEM_ACTOR,
            department(null, "Lifecycle Deleted Department", 0));
        departments.deleteDepartment(TENANT_ID, SYSTEM_ACTOR, deletedDepartment, 0L);

        List<Long> managedDepartmentIds = queries.findDepartments(TENANT_ID, false).stream()
            .map(IdentityModels.Department::id).toList();
        List<Long> selectableDepartmentIds = queries.findDepartments(TENANT_ID, true).stream()
            .map(IdentityModels.Department::id).toList();
        assertTrue(managedDepartmentIds.containsAll(List.of(activeDepartment, disabledDepartment)));
        assertFalse(managedDepartmentIds.contains(deletedDepartment));
        assertTrue(selectableDepartmentIds.contains(activeDepartment));
        assertFalse(selectableDepartmentIds.contains(disabledDepartment));
        assertFalse(selectableDepartmentIds.contains(deletedDepartment));

        long activeMenu = menus.createMenu(TENANT_ID, SYSTEM_ACTOR,
            menu(null, "LifecycleActiveMenu", "/lifecycle-active-menu", 1));
        long disabledMenu = menus.createMenu(TENANT_ID, SYSTEM_ACTOR,
            menu(null, "LifecycleDisabledMenu", "/lifecycle-disabled-menu", 0));
        long deletedMenu = menus.createMenu(TENANT_ID, SYSTEM_ACTOR,
            menu(null, "LifecycleDeletedMenu", "/lifecycle-deleted-menu", 0));
        menus.deleteMenu(TENANT_ID, SYSTEM_ACTOR, deletedMenu, 0L);

        List<Long> managedMenuIds = queries.findMenus(TENANT_ID, false).stream()
            .map(IdentityModels.Menu::id).toList();
        List<Long> selectableMenuIds = queries.findMenus(TENANT_ID, true).stream()
            .map(IdentityModels.Menu::id).toList();
        assertTrue(managedMenuIds.containsAll(List.of(activeMenu, disabledMenu)));
        assertFalse(managedMenuIds.contains(deletedMenu));
        assertTrue(selectableMenuIds.contains(activeMenu));
        assertFalse(selectableMenuIds.contains(disabledMenu));
        assertFalse(selectableMenuIds.contains(deletedMenu));
    }

    @Test
    void departmentDeletionIsSoftAndRejectsProtectedOrReferencedRows() {
        long systemManaged = departments.createDepartment(TENANT_ID, SYSTEM_ACTOR,
            department(null, "Lifecycle Managed Department", 1));
        dsl.update(IAM_DEPARTMENT).set(IAM_DEPARTMENT.SYSTEM_MANAGED, true)
            .where(IAM_DEPARTMENT.ID.eq(systemManaged)).execute();
        assertThrows(IdentityAdministrationService.DataConflictException.class,
            () -> departments.updateDepartment(TENANT_ID, SYSTEM_ACTOR, systemManaged,
                department(null, "Renamed Managed Department", 1), 0L));
        assertThrows(IdentityAdministrationService.DataConflictException.class,
            () -> departments.deleteDepartment(TENANT_ID, SYSTEM_ACTOR, systemManaged, 0L));

        long parent = departments.createDepartment(TENANT_ID, SYSTEM_ACTOR,
            department(null, "Lifecycle Department Parent", 1));
        departments.createDepartment(TENANT_ID, SYSTEM_ACTOR,
            department(parent, "Lifecycle Disabled Child", 0));
        assertThrows(IdentityAdministrationService.DataConflictException.class,
            () -> departments.deleteDepartment(TENANT_ID, SYSTEM_ACTOR, parent, 0L));

        long assigned = departments.createDepartment(TENANT_ID, SYSTEM_ACTOR,
            department(null, "Lifecycle Assigned Department", 0));
        long assignedUser = 9_310_500L;
        seedUser(assignedUser, "lifecycle-disabled-member", "Disabled Member");
        seedMembership(9_310_501L, TENANT_ID, assignedUser, assigned, "DISABLED");
        assertThrows(IdentityAdministrationService.DataConflictException.class,
            () -> departments.deleteDepartment(TENANT_ID, SYSTEM_ACTOR, assigned, 0L));

        long granted = departments.createDepartment(TENANT_ID, SYSTEM_ACTOR,
            department(null, "Lifecycle Granted Department", 0));
        seedDepartmentGrant(granted);
        assertThrows(IdentityAdministrationService.DataConflictException.class,
            () -> departments.deleteDepartment(TENANT_ID, SYSTEM_ACTOR, granted, 0L));

        long free = departments.createDepartment(TENANT_ID, SYSTEM_ACTOR,
            department(null, "Lifecycle Free Department", 0));
        departments.deleteDepartment(TENANT_ID, SYSTEM_ACTOR, free, 0L);
        assertEquals("DISABLED", dsl.select(IAM_DEPARTMENT.STATUS).from(IAM_DEPARTMENT)
            .where(IAM_DEPARTMENT.ID.eq(free)).fetchSingle(IAM_DEPARTMENT.STATUS));
        assertNotNull(dsl.select(IAM_DEPARTMENT.DELETED_AT).from(IAM_DEPARTMENT)
            .where(IAM_DEPARTMENT.ID.eq(free)).fetchSingle(IAM_DEPARTMENT.DELETED_AT));
    }

    @Test
    void menuDeletionIsSoftAndRejectsProtectedRowsOrDisabledChildren() {
        long systemManaged = menus.createMenu(TENANT_ID, SYSTEM_ACTOR,
            menu(null, "LifecycleManagedMenu", "/lifecycle-managed-menu", 1));
        dsl.update(IAM_MENU).set(IAM_MENU.SYSTEM_MANAGED, true)
            .where(IAM_MENU.ID.eq(systemManaged)).execute();
        assertThrows(IdentityAdministrationService.DataConflictException.class,
            () -> menus.updateMenu(TENANT_ID, SYSTEM_ACTOR, systemManaged,
                menu(null, "LifecycleManagedMenu", "/lifecycle-managed-menu", 1), 0L));
        assertThrows(IdentityAdministrationService.DataConflictException.class,
            () -> menus.deleteMenu(TENANT_ID, SYSTEM_ACTOR, systemManaged, 0L));

        long parent = menus.createMenu(TENANT_ID, SYSTEM_ACTOR,
            menu(null, "LifecycleMenuParent", "/lifecycle-menu-parent", 1));
        menus.createMenu(TENANT_ID, SYSTEM_ACTOR,
            menu(parent, "LifecycleDisabledMenuChild", "/lifecycle-disabled-menu-child", 0));
        assertThrows(IdentityAdministrationService.DataConflictException.class,
            () -> menus.deleteMenu(TENANT_ID, SYSTEM_ACTOR, parent, 0L));

        long free = menus.createMenu(TENANT_ID, SYSTEM_ACTOR,
            menu(null, "LifecycleFreeMenu", "/lifecycle-free-menu", 0));
        menus.deleteMenu(TENANT_ID, SYSTEM_ACTOR, free, 0L);
        assertEquals("DISABLED", dsl.select(IAM_MENU.STATUS).from(IAM_MENU)
            .where(IAM_MENU.ID.eq(free)).fetchSingle(IAM_MENU.STATUS));
        assertNotNull(dsl.select(IAM_MENU.DELETED_AT).from(IAM_MENU)
            .where(IAM_MENU.ID.eq(free)).fetchSingle(IAM_MENU.DELETED_AT));
    }

    @Test
    void systemAdministratorCanUpdateIdentityWithThreeWayVersionChecks() {
        users.updateUser(TENANT_ID, SYSTEM_ACTOR, TARGET_USER_ID,
            new IdentityModels.MembershipUpdateCommand(
                "lifecycle-renamed", "Lifecycle Renamed", ROOT_DEPARTMENT_ID, List.of(), 1,
                0L, 0L, 0L, "renamed remark"));

        assertEquals("lifecycle-renamed", dsl.select(IAM_AUTHENTICATION_CREDENTIAL.USERNAME)
            .from(IAM_AUTHENTICATION_CREDENTIAL).where(IAM_AUTHENTICATION_CREDENTIAL.USER_ID.eq(TARGET_USER_ID))
            .fetchSingle(IAM_AUTHENTICATION_CREDENTIAL.USERNAME));
        assertEquals("lifecycle-renamed", dsl.select(IAM_USER.IDP_SUBJECT).from(IAM_USER)
            .where(IAM_USER.ID.eq(TARGET_USER_ID)).fetchSingle(IAM_USER.IDP_SUBJECT));
        assertEquals("Lifecycle Renamed", dsl.select(IAM_USER.DISPLAY_NAME).from(IAM_USER)
            .where(IAM_USER.ID.eq(TARGET_USER_ID)).fetchSingle(IAM_USER.DISPLAY_NAME));
        assertEquals("renamed remark", dsl.select(IAM_USER.REMARK).from(IAM_USER)
            .where(IAM_USER.ID.eq(TARGET_USER_ID)).fetchSingle(IAM_USER.REMARK));
        assertEquals(1L, membershipValue(IAM_MEMBERSHIP.ROW_VERSION, TARGET_MEMBERSHIP_ID));
        assertEquals(1L, userValue(IAM_USER.ROW_VERSION, TARGET_USER_ID));
        assertEquals(1L, credentialValue(IAM_AUTHENTICATION_CREDENTIAL.ROW_VERSION, TARGET_USER_ID));
        assertEquals(1L, membershipValue(IAM_MEMBERSHIP.SESSION_VERSION, OTHER_MEMBERSHIP_ID));
        assertEquals(0L, membershipValue(IAM_MEMBERSHIP.SESSION_VERSION, TERMINATED_MEMBERSHIP_ID));

        IdentityModels.User view = queries.findUsers(TENANT_ID,
                new IdentityModels.UserQuery("lifecycle-renamed", null, null, null, null,
                    null, null, 1, 20))
            .items().getFirst();
        assertEquals(1L, view.userVersion());
        assertEquals(1L, view.identityVersion());
        assertEquals(1L, view.credentialVersion());

        assertThrows(IdentityAdministrationService.OptimisticLockException.class,
            () -> users.updateUser(TENANT_ID, SYSTEM_ACTOR, TARGET_USER_ID,
                new IdentityModels.MembershipUpdateCommand(
                    "lifecycle-renamed", "Lifecycle Renamed", ROOT_DEPARTMENT_ID, List.of(), 1,
                    1L, 0L, 1L, "renamed remark")));

        dsl.update(IAM_MEMBERSHIP).set(IAM_MEMBERSHIP.ROW_VERSION, 128L)
            .where(IAM_MEMBERSHIP.ID.eq(TARGET_MEMBERSHIP_ID)).execute();
        dsl.update(IAM_USER).set(IAM_USER.ROW_VERSION, 128L)
            .where(IAM_USER.ID.eq(TARGET_USER_ID)).execute();
        dsl.update(IAM_AUTHENTICATION_CREDENTIAL)
            .set(IAM_AUTHENTICATION_CREDENTIAL.ROW_VERSION, 128L)
            .where(IAM_AUTHENTICATION_CREDENTIAL.USER_ID.eq(TARGET_USER_ID)).execute();
        users.updateUser(TENANT_ID, SYSTEM_ACTOR, TARGET_USER_ID,
            new IdentityModels.MembershipUpdateCommand(
                "lifecycle-renamed", "Lifecycle Renamed", ROOT_DEPARTMENT_ID, List.of(), 1,
                128L, 128L, 128L, "renamed remark"));
        assertEquals(129L, membershipValue(IAM_MEMBERSHIP.ROW_VERSION, TARGET_MEMBERSHIP_ID));
    }

    @Test
    void identityChangesRequireSystemAdministrationAndUniqueUsername() {
        long membershipVersion = membershipValue(IAM_MEMBERSHIP.ROW_VERSION, TARGET_MEMBERSHIP_ID);
        long identityVersion = userValue(IAM_USER.ROW_VERSION, TARGET_USER_ID);
        long credentialVersion = credentialValue(IAM_AUTHENTICATION_CREDENTIAL.ROW_VERSION, TARGET_USER_ID);

        assertThrows(SecurityException.class,
            () -> users.updateUser(TENANT_ID, PLAIN_ACTOR, TARGET_USER_ID,
                new IdentityModels.MembershipUpdateCommand(
                    "plain-actor-rejected", "Lifecycle Rejected", ROOT_DEPARTMENT_ID, List.of(), 1,
                    membershipVersion, identityVersion, credentialVersion, "rejected")));

        seedUser(CONFLICT_USER_ID, "lifecycle-conflict", "Conflict User");
        assertThrows(IdentityAdministrationService.DataConflictException.class,
            () -> users.updateUser(TENANT_ID, SYSTEM_ACTOR, TARGET_USER_ID,
                new IdentityModels.MembershipUpdateCommand(
                    "lifecycle-conflict", currentDisplayName(), ROOT_DEPARTMENT_ID, List.of(), 1,
                    membershipVersion, identityVersion, credentialVersion, currentRemark())));
    }

    private static void seedEditableTarget() {
        seedUser(TARGET_USER_ID, "lifecycle-target", "Lifecycle Target");
        seedMembership(TARGET_MEMBERSHIP_ID, TENANT_ID, TARGET_USER_ID, ROOT_DEPARTMENT_ID, "ACTIVE");
        seedMembership(OTHER_MEMBERSHIP_ID, OTHER_TENANT_ID, TARGET_USER_ID, OTHER_ROOT_DEPARTMENT_ID, "DISABLED");
        seedMembership(TERMINATED_MEMBERSHIP_ID, TERMINATED_TENANT_ID, TARGET_USER_ID,
            TERMINATED_ROOT_DEPARTMENT_ID, "TERMINATED");
    }

    private static void seedDepartmentGrant(long departmentId) {
        long roleId = 9_310_600L;
        long grantId = 9_310_601L;
        long dimensionId = 9_310_602L;
        dsl.insertInto(IAM_ROLE,
                IAM_ROLE.ID, IAM_ROLE.TENANT_ID, IAM_ROLE.ROLE_CODE, IAM_ROLE.ROLE_NAME,
                IAM_ROLE.APPLICABLE_TENANT_TYPE, IAM_ROLE.ASSIGNABLE, IAM_ROLE.SYSTEM_ROLE,
                IAM_ROLE.STATUS)
            .values(roleId, TENANT_ID, "lifecycle-granted-role", "Lifecycle Granted Role",
                "PLATFORM", true, false, "ACTIVE")
            .execute();
        dsl.insertInto(IAM_ROLE_GRANT,
                IAM_ROLE_GRANT.ID, IAM_ROLE_GRANT.TENANT_ID, IAM_ROLE_GRANT.ROLE_ID,
                IAM_ROLE_GRANT.PERMISSION_ID, IAM_ROLE_GRANT.GRANT_KEY, IAM_ROLE_GRANT.STATUS)
            .values(grantId, TENANT_ID, roleId, 3001L, "lifecycle-department-grant", "ACTIVE")
            .execute();
        dsl.insertInto(IAM_GRANT_DIMENSION,
                IAM_GRANT_DIMENSION.ID, IAM_GRANT_DIMENSION.GRANT_ID,
                IAM_GRANT_DIMENSION.DIMENSION_CODE, IAM_GRANT_DIMENSION.SCOPE_MODE)
            .values(dimensionId, grantId, "DEPARTMENT", "SPECIFIED")
            .execute();
        dsl.insertInto(IAM_GRANT_TARGET,
                IAM_GRANT_TARGET.ID, IAM_GRANT_TARGET.DIMENSION_ID, IAM_GRANT_TARGET.TARGET_REF)
            .values(9_310_603L, dimensionId, Long.toString(departmentId))
            .execute();
    }

    private static void seedTenant(long tenantId, String code, long departmentId) {
        dsl.insertInto(IAM_TENANT,
                IAM_TENANT.ID, IAM_TENANT.TENANT_CODE, IAM_TENANT.TENANT_NAME,
                IAM_TENANT.TENANT_TYPE, IAM_TENANT.STATUS)
            .values(tenantId, code, code, "PLATFORM", "ACTIVE")
            .execute();
        dsl.insertInto(IAM_DEPARTMENT,
                IAM_DEPARTMENT.ID, IAM_DEPARTMENT.TENANT_ID, IAM_DEPARTMENT.DEPARTMENT_CODE,
                IAM_DEPARTMENT.DEPARTMENT_NAME, IAM_DEPARTMENT.STATUS)
            .values(departmentId, tenantId, code + "-root", code + " root", "ACTIVE")
            .execute();
    }

    private static void seedUser(long userId, String username, String displayName) {
        dsl.insertInto(IAM_USER,
                IAM_USER.ID, IAM_USER.IDP_ISSUER, IAM_USER.IDP_SUBJECT,
                IAM_USER.DISPLAY_NAME, IAM_USER.STATUS)
            .values(userId, "local", username, displayName, "ACTIVE")
            .execute();
        dsl.insertInto(IAM_AUTHENTICATION_CREDENTIAL,
                IAM_AUTHENTICATION_CREDENTIAL.USER_ID, IAM_AUTHENTICATION_CREDENTIAL.USERNAME,
                IAM_AUTHENTICATION_CREDENTIAL.PASSWORD_HASH, IAM_AUTHENTICATION_CREDENTIAL.STATUS)
            .values(userId, username, SUPPORTED_DUMMY_HASH, "ACTIVE")
            .execute();
    }

    private static void seedMembership(long membershipId, long tenantId, long userId,
                                       long departmentId, String status) {
        dsl.insertInto(IAM_MEMBERSHIP,
                IAM_MEMBERSHIP.ID, IAM_MEMBERSHIP.TENANT_ID, IAM_MEMBERSHIP.USER_ID,
                IAM_MEMBERSHIP.DEPARTMENT_ID, IAM_MEMBERSHIP.STATUS)
            .values(membershipId, tenantId, userId, departmentId, status)
            .execute();
    }

    private static IdentityModels.DepartmentCommand department(Long parentId, String name, int status) {
        return new IdentityModels.DepartmentCommand(parentId, name, status, null);
    }

    private static IdentityModels.MenuCommand menu(Long parentId, String name, String path, int status) {
        return new IdentityModels.MenuCommand(parentId, "menu", name, path,
            "/system/menu/list", null, null, "{}", status);
    }

    private static long membershipValue(org.jooq.Field<Long> field, long membershipId) {
        return dsl.select(field).from(IAM_MEMBERSHIP)
            .where(IAM_MEMBERSHIP.ID.eq(membershipId)).fetchSingle(field);
    }

    private static long userValue(org.jooq.Field<Long> field, long userId) {
        return dsl.select(field).from(IAM_USER).where(IAM_USER.ID.eq(userId)).fetchSingle(field);
    }

    private static long credentialValue(org.jooq.Field<Long> field, long userId) {
        return dsl.select(field).from(IAM_AUTHENTICATION_CREDENTIAL)
            .where(IAM_AUTHENTICATION_CREDENTIAL.USER_ID.eq(userId)).fetchSingle(field);
    }

    private static String currentDisplayName() {
        return dsl.select(IAM_USER.DISPLAY_NAME).from(IAM_USER)
            .where(IAM_USER.ID.eq(TARGET_USER_ID)).fetchSingle(IAM_USER.DISPLAY_NAME);
    }

    private static String currentRemark() {
        return dsl.select(IAM_USER.REMARK).from(IAM_USER)
            .where(IAM_USER.ID.eq(TARGET_USER_ID)).fetchSingle(IAM_USER.REMARK);
    }
}
