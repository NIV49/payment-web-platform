package com.niv.payment.adminapi;

import com.niv.payment.permission.domain.AdministrationActor;
import com.niv.payment.permission.persistence.repository.JooqIdentityQueryRepository;
import com.niv.payment.permission.persistence.repository.JooqMenuAdministrationRepository;
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
import java.util.ArrayList;
import java.util.List;

import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_AUTHENTICATION_CREDENTIAL;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_DEPARTMENT;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_MEMBERSHIP;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_MENU;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_TENANT;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_USER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static com.niv.payment.permission.service.IdentityAdministrationService.MAX_TREE_DEPTH;
import static com.niv.payment.permission.service.IdentityAdministrationService.MAX_TREE_NODES;

@Testcontainers
class JooqMenuAdministrationRepositoryIntegrationTest {
    private static final String SUPPORTED_DUMMY_HASH =
        "$2a$12$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";
    private static final long TENANT_A = 9_210_000L;
    private static final long TENANT_B = 9_220_000L;
    private static final long USER_A = 9_210_001L;
    private static final long USER_B = 9_220_001L;
    private static final long MEMBERSHIP_A = 9_210_002L;
    private static final long MEMBERSHIP_B = 9_220_002L;
    private static final AdministrationActor ACTOR_A =
        new AdministrationActor(MEMBERSHIP_A, USER_A, 0L, 0L);
    private static final AdministrationActor ACTOR_B =
        new AdministrationActor(MEMBERSHIP_B, USER_B, 0L, 0L);

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse(
        "postgres:18.4-alpine@sha256:9a8afca54e7861fd90fab5fdf4c42477a6b1cb7d293595148e674e0a3181de15")
        .asCompatibleSubstituteFor("postgres"))
        .withDatabaseName("payment_platform")
        .withUsername("payment_dev")
        .withPassword("payment_dev");

    private static Connection connection;
    private static DSLContext dsl;
    private static JooqMenuAdministrationRepository repository;

    @BeforeAll
    static void migrateAndSeedOperators() throws Exception {
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration")
            .load()
            .migrate();
        connection = DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        dsl = DSL.using(connection, SQLDialect.POSTGRES);
        seedOperator(TENANT_A, USER_A, MEMBERSHIP_A, "menu-operator-a");
        seedOperator(TENANT_B, USER_B, MEMBERSHIP_B, "menu-operator-b");
        repository = new JooqMenuAdministrationRepository(
            dsl, new JooqIdentityQueryRepository(dsl), () -> "menu-invariant-test");
    }

    @AfterAll
    static void closeConnection() throws Exception {
        if (connection != null) {
            connection.close();
        }
    }

    @Test
    void activeChildCannotBeCreatedOrMovedBelowDisabledParent() {
        long disabledParent = repository.createMenu(
            TENANT_A, ACTOR_A, command(null, "DisabledParent", "/disabled-parent", 0));

        assertThrows(IllegalArgumentException.class, () -> repository.createMenu(
            TENANT_A, ACTOR_A, command(disabledParent, "RejectedActiveChild", "/rejected-child", 1)));
        assertEquals(0, dsl.fetchCount(IAM_MENU,
            IAM_MENU.TENANT_ID.eq(TENANT_A)
                .and(IAM_MENU.ROUTE_NAME.eq("RejectedActiveChild"))));

        long activeChild = repository.createMenu(
            TENANT_A, ACTOR_A, command(null, "MovableActiveChild", "/movable-child", 1));
        assertThrows(IllegalArgumentException.class, () -> repository.updateMenu(
            TENANT_A, ACTOR_A, activeChild,
            command(disabledParent, "MovableActiveChild", "/movable-child", 1), 0L));
        assertNull(dsl.select(IAM_MENU.PARENT_ID)
            .from(IAM_MENU)
            .where(IAM_MENU.TENANT_ID.eq(TENANT_A).and(IAM_MENU.ID.eq(activeChild)))
            .fetchSingle(IAM_MENU.PARENT_ID));
    }

    @Test
    void buttonCannotBecomeAParentAndMustReferenceAnActiveCatalogPermission() {
        long button = repository.createMenu(TENANT_A, ACTOR_A,
            new IdentityModels.MenuCommand(null, "button", "PermissionButton", null, null,
                null, "user:view", "{}", 1));

        assertThrows(IllegalArgumentException.class, () -> repository.createMenu(
            TENANT_A, ACTOR_A, command(button, "RejectedButtonChild", "/button-child", 1)));
        assertThrows(IllegalArgumentException.class, () -> repository.createMenu(
            TENANT_A, ACTOR_A, new IdentityModels.MenuCommand(null, "button", "UnknownPermissionButton",
                null, null, null, "unknown:permission", "{}", 1)));

        long parent = repository.createMenu(
            TENANT_A, ACTOR_A, command(null, "ParentWithChild", "/parent-with-child", 1));
        repository.createMenu(
            TENANT_A, ACTOR_A, command(parent, "ExistingChild", "/existing-child", 1));
        assertThrows(IllegalArgumentException.class, () -> repository.updateMenu(
            TENANT_A, ACTOR_A, parent,
            new IdentityModels.MenuCommand(null, "button", "ParentWithChild", null, null,
                null, "user:view", "{}", 1), 0L));
    }

    @Test
    void activeDeepDescendantBlocksAncestorDisableAndDelete() {
        long root = repository.createMenu(
            TENANT_A, ACTOR_A, command(null, "DeepRoot", "/deep-root", 1));
        long branch = repository.createMenu(
            TENANT_A, ACTOR_A, command(root, "DeepBranch", "/deep-branch", 1));
        repository.createMenu(
            TENANT_A, ACTOR_A, command(branch, "DeepLeaf", "/deep-leaf", 1));

        assertThrows(IdentityAdministrationService.DataConflictException.class,
            () -> repository.updateMenu(
                TENANT_A, ACTOR_A, root, command(null, "DeepRoot", "/deep-root", 0), 0L));
        assertThrows(IdentityAdministrationService.DataConflictException.class,
            () -> repository.deleteMenu(TENANT_A, ACTOR_A, root, 0L));
        assertEquals("ACTIVE", dsl.select(IAM_MENU.STATUS)
            .from(IAM_MENU)
            .where(IAM_MENU.TENANT_ID.eq(TENANT_A).and(IAM_MENU.ID.eq(root)))
            .fetchSingle(IAM_MENU.STATUS));
    }

    @Test
    void sameTenantRejectsCanonicalRouteNameAndPathDuplicates() {
        repository.createMenu(
            TENANT_A, ACTOR_A, command(null, "UniqueRouteName", "/unique-route", 1));

        assertThrows(IdentityAdministrationService.DataConflictException.class,
            () -> repository.createMenu(
                TENANT_A, ACTOR_A, command(null, "uniqueroutename", "/different-route", 1)));
        assertThrows(IdentityAdministrationService.DataConflictException.class,
            () -> repository.createMenu(
                TENANT_A, ACTOR_A, command(null, "DifferentRouteName", "/unique-route", 1)));
        assertThrows(IdentityAdministrationService.DataConflictException.class,
            () -> repository.createMenu(
                TENANT_A, ACTOR_A, command(null, "CanonicalPathCollision", "/UNIQUE-ROUTE///", 1)));
        assertTrue(repository.menuPathExists(TENANT_A, "/Unique-Route////", null));
        assertEquals(1, dsl.fetchCount(IAM_MENU,
            IAM_MENU.TENANT_ID.eq(TENANT_A)
                .and(IAM_MENU.ROUTE_PATH.eq("/unique-route"))));
    }

    @Test
    void differentTenantsMayReuseRouteNameAndPath() {
        long first = repository.createMenu(
            TENANT_A, ACTOR_A, command(null, "SharedTenantRoute", "/Shared-Tenant-Route///", 1));
        long second = repository.createMenu(
            TENANT_B, ACTOR_B, command(null, "sharedtenantroute", "/shared-tenant-route", 1));

        assertEquals(2, dsl.fetchCount(IAM_MENU,
            IAM_MENU.ID.in(first, second)
                .and(DSL.lower(IAM_MENU.ROUTE_NAME).eq("sharedtenantroute"))));
    }

    @Test
    void createRejectsMenusBeyondTheMaximumTreeDepth() {
        List<Long> created = new ArrayList<>();
        Long parentId = null;
        try {
            for (int depth = 1; depth <= MAX_TREE_DEPTH; depth++) {
                parentId = repository.createMenu(TENANT_A, ACTOR_A,
                    command(parentId, "DepthRoute" + depth, "/depth-route-" + depth, 1));
                created.add(parentId);
            }
            Long deepestParent = parentId;
            assertThrows(IllegalArgumentException.class, () -> repository.createMenu(
                TENANT_A, ACTOR_A,
                command(deepestParent, "DepthOverflow", "/depth-overflow", 1)));

            long extraRoot = repository.createMenu(TENANT_A, ACTOR_A,
                command(null, "DepthMoveRoot", "/depth-move-root", 1));
            created.add(extraRoot);
            long chainRoot = created.getFirst();
            assertThrows(IllegalArgumentException.class, () -> repository.updateMenu(
                TENANT_A, ACTOR_A, chainRoot,
                command(extraRoot, "DepthRoute1", "/depth-route-1", 1), 0L));
        } finally {
            dsl.deleteFrom(IAM_MENU)
                .where(IAM_MENU.TENANT_ID.eq(TENANT_A)
                    .and(IAM_MENU.ROUTE_NAME.eq("DepthOverflow")))
                .execute();
            for (int index = created.size() - 1; index >= 0; index--) {
                dsl.deleteFrom(IAM_MENU).where(IAM_MENU.ID.eq(created.get(index))).execute();
            }
        }
    }

    @Test
    void mutationFailsClosedWhenTenantMenuTreeExceedsTheNodeLimit() {
        long firstId = 9_290_000L;
        try {
            dsl.execute("""
                INSERT INTO iam_menu(id,tenant_id,menu_type,menu_name,route_name,route_path,
                                     sort_order,status,meta_json)
                SELECT ? + n, ?, 'DIRECTORY', 'LimitRoute' || n, 'LimitRoute' || n,
                       '/limit-route-' || n, n, 'ACTIVE', '{}'::jsonb
                  FROM generate_series(1, ?) AS n
                """, firstId, TENANT_A, MAX_TREE_NODES + 1);

            assertThrows(IdentityAdministrationService.TreeLimitExceededException.class,
                () -> repository.createMenu(TENANT_A, ACTOR_A,
                    command(null, "RejectedByNodeLimit", "/rejected-by-node-limit", 1)));
            assertThrows(IdentityAdministrationService.TreeLimitExceededException.class,
                () -> repository.updateMenu(TENANT_A, ACTOR_A, firstId + 1,
                    command(null, "LimitRoute1", "/limit-route-1", 1), 0L));
        } finally {
            dsl.deleteFrom(IAM_MENU)
                .where(IAM_MENU.ID.between(firstId + 1, firstId + MAX_TREE_NODES + 1L))
                .execute();
        }
    }

    private static IdentityModels.MenuCommand command(Long parentId, String name,
                                                       String path, int status) {
        return new IdentityModels.MenuCommand(
            parentId, "menu", name, path, "/system/menu/test", null, null, "{}", status);
    }

    private static void seedOperator(long tenantId, long userId, long membershipId,
                                     String username) {
        long departmentId = tenantId + 10L;
        dsl.insertInto(IAM_TENANT,
                IAM_TENANT.ID, IAM_TENANT.TENANT_CODE, IAM_TENANT.TENANT_NAME,
                IAM_TENANT.TENANT_TYPE, IAM_TENANT.STATUS)
            .values(tenantId, username, username, "PLATFORM", "ACTIVE")
            .execute();
        dsl.insertInto(IAM_DEPARTMENT,
                IAM_DEPARTMENT.ID, IAM_DEPARTMENT.TENANT_ID, IAM_DEPARTMENT.DEPARTMENT_CODE,
                IAM_DEPARTMENT.DEPARTMENT_NAME, IAM_DEPARTMENT.STATUS)
            .values(departmentId, tenantId, "root", "Root", "ACTIVE")
            .execute();
        dsl.insertInto(IAM_USER,
                IAM_USER.ID, IAM_USER.IDP_ISSUER, IAM_USER.IDP_SUBJECT,
                IAM_USER.DISPLAY_NAME, IAM_USER.STATUS)
            .values(userId, "local", username, username, "ACTIVE")
            .execute();
        dsl.insertInto(IAM_MEMBERSHIP,
                IAM_MEMBERSHIP.ID, IAM_MEMBERSHIP.TENANT_ID, IAM_MEMBERSHIP.USER_ID,
                IAM_MEMBERSHIP.DEPARTMENT_ID, IAM_MEMBERSHIP.STATUS)
            .values(membershipId, tenantId, userId, departmentId, "ACTIVE")
            .execute();
        dsl.insertInto(IAM_AUTHENTICATION_CREDENTIAL,
                IAM_AUTHENTICATION_CREDENTIAL.USER_ID, IAM_AUTHENTICATION_CREDENTIAL.USERNAME,
                IAM_AUTHENTICATION_CREDENTIAL.PASSWORD_HASH, IAM_AUTHENTICATION_CREDENTIAL.STATUS)
            .values(userId, username, SUPPORTED_DUMMY_HASH, "ACTIVE")
            .execute();
    }
}
