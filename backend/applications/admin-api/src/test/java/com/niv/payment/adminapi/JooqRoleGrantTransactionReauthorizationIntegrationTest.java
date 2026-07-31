package com.niv.payment.adminapi;

import com.niv.payment.permission.domain.AdministrationActor;
import com.niv.payment.permission.persistence.repository.JooqRoleGrantAdministrationRepository;
import com.niv.payment.permission.service.RoleGrantChangeCommand;
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
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_AUDIT_EVENT;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_AUTHENTICATION_CREDENTIAL;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_GRANT_DIMENSION;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_MEMBERSHIP;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_MEMBERSHIP_ROLE;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_PERMISSION;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_PERMISSION_CHANGE_OUTBOX;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_ROLE;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_ROLE_GRANT;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_TENANT;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_USER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

@Testcontainers
class JooqRoleGrantTransactionReauthorizationIntegrationTest {
    private static final long TENANT_ID = 9_840_000L;
    private static final long USER_ID = 9_840_001L;
    private static final long MEMBERSHIP_ID = 9_840_002L;
    private static final long SYSTEM_ROLE_ID = 9_840_003L;
    private static final long EXPIRY_TARGET_ROLE_ID = 9_840_004L;
    private static final long MISSING_PERMISSION_TARGET_ROLE_ID = 9_840_005L;
    private static final long ROLE_VIEW_GRANT_ID = 9_840_006L;
    private static final long GRANT_UPDATE_GRANT_ID = 9_840_007L;
    private static final long EXPIRY_TARGET_GRANT_ID = 9_840_008L;
    private static final long MISSING_PERMISSION_TARGET_GRANT_ID = 9_840_009L;
    private static final long PERMISSION_VERSION = 31L;
    private static final long SESSION_VERSION = 47L;
    private static final String TEST_PASSWORD_HASH =
        "$2a$12$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse(
        "postgres:18.4-alpine@sha256:9a8afca54e7861fd90fab5fdf4c42477a6b1cb7d293595148e674e0a3181de15")
        .asCompatibleSubstituteFor("postgres"))
        .withDatabaseName("payment_platform")
        .withUsername("payment_dev")
        .withPassword("payment_dev");

    private static Connection connection;
    private static DSLContext dsl;

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
        seedAuthorizationFixture();
    }

    @AfterAll
    static void closeConnection() throws Exception {
        if (connection != null) {
            connection.close();
        }
    }

    @Test
    void roleGrantReplacementReauthorizesAfterLockWaitUsingCurrentDatabaseTime() throws Exception {
        OffsetDateTime expiresAt = databaseTime().plusSeconds(2);
        dsl.update(IAM_ROLE_GRANT)
            .set(IAM_ROLE_GRANT.VALID_UNTIL, expiresAt)
            .where(IAM_ROLE_GRANT.ID.in(ROLE_VIEW_GRANT_ID, GRANT_UPDATE_GRANT_ID))
            .execute();

        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<Throwable> operation = null;
        try (Connection lockerConnection = DriverManager.getConnection(
                 POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Connection workerConnection = DriverManager.getConnection(
                 POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            lockerConnection.setAutoCommit(false);
            workerConnection.setAutoCommit(false);
            DSLContext locker = DSL.using(lockerConnection, SQLDialect.POSTGRES);
            DSLContext worker = DSL.using(workerConnection, SQLDialect.POSTGRES);
            int workerPid = worker.select(DSL.field("pg_backend_pid()", Integer.class))
                .fetchSingle()
                .value1();

            locker.select(IAM_TENANT.ID)
                .from(IAM_TENANT)
                .where(IAM_TENANT.ID.eq(TENANT_ID))
                .forUpdate()
                .fetchSingle();

            operation = executor.submit(() -> executeReplacement(
                workerConnection, worker, EXPIRY_TARGET_ROLE_ID));
            awaitLockWait(workerPid);
            awaitDatabaseTime(expiresAt);
            lockerConnection.commit();

            Throwable failure = operation.get(5, TimeUnit.SECONDS);
            assertInstanceOf(SecurityException.class, failure);
            assertTargetUnchanged(EXPIRY_TARGET_ROLE_ID, EXPIRY_TARGET_GRANT_ID);
        } finally {
            if (operation != null && !operation.isDone()) {
                operation.cancel(true);
            }
            executor.shutdownNow();
            dsl.update(IAM_ROLE_GRANT)
                .setNull(IAM_ROLE_GRANT.VALID_UNTIL)
                .where(IAM_ROLE_GRANT.ID.in(ROLE_VIEW_GRANT_ID, GRANT_UPDATE_GRANT_ID))
                .execute();
        }
    }

    @Test
    void roleGrantReplacementRequiresBothTransactionalPermissions() throws Exception {
        for (long missingGrantId : List.of(ROLE_VIEW_GRANT_ID, GRANT_UPDATE_GRANT_ID)) {
            dsl.update(IAM_ROLE_GRANT)
                .set(IAM_ROLE_GRANT.STATUS, "DISABLED")
                .where(IAM_ROLE_GRANT.ID.eq(missingGrantId))
                .execute();

            try (Connection workerConnection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
                workerConnection.setAutoCommit(false);
                DSLContext worker = DSL.using(workerConnection, SQLDialect.POSTGRES);

                Throwable failure = executeReplacement(
                    workerConnection, worker, MISSING_PERMISSION_TARGET_ROLE_ID);

                assertInstanceOf(SecurityException.class, failure);
                assertTargetUnchanged(
                    MISSING_PERMISSION_TARGET_ROLE_ID, MISSING_PERMISSION_TARGET_GRANT_ID);
            } finally {
                dsl.update(IAM_ROLE_GRANT)
                    .set(IAM_ROLE_GRANT.STATUS, "ACTIVE")
                    .where(IAM_ROLE_GRANT.ID.eq(missingGrantId))
                    .execute();
            }
        }
    }

    private static Throwable executeReplacement(
        Connection workerConnection, DSLContext worker, long targetRoleId) {
        var repository = new JooqRoleGrantAdministrationRepository(
            worker, () -> "transaction-reauthorization-test");
        try {
            repository.replaceAtomically(new RoleGrantChangeCommand(
                TENANT_ID,
                targetRoleId,
                0L,
                new AdministrationActor(
                    MEMBERSHIP_ID, USER_ID, PERMISSION_VERSION, SESSION_VERSION),
                "transaction reauthorization test",
                List.of()));
            workerConnection.commit();
            return null;
        } catch (Throwable failure) {
            try {
                workerConnection.rollback();
            } catch (Exception rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            return failure;
        }
    }

    private static void assertTargetUnchanged(long targetRoleId, long targetGrantId) {
        assertEquals(PERMISSION_VERSION, dsl.select(IAM_MEMBERSHIP.PERMISSION_VERSION)
            .from(IAM_MEMBERSHIP)
            .where(IAM_MEMBERSHIP.TENANT_ID.eq(TENANT_ID)
                .and(IAM_MEMBERSHIP.ID.eq(MEMBERSHIP_ID)))
            .fetchSingle(IAM_MEMBERSHIP.PERMISSION_VERSION));
        assertEquals(0L, dsl.select(IAM_ROLE.ROW_VERSION)
            .from(IAM_ROLE)
            .where(IAM_ROLE.TENANT_ID.eq(TENANT_ID).and(IAM_ROLE.ID.eq(targetRoleId)))
            .fetchSingle(IAM_ROLE.ROW_VERSION));
        assertEquals("ACTIVE", dsl.select(IAM_ROLE_GRANT.STATUS)
            .from(IAM_ROLE_GRANT)
            .where(IAM_ROLE_GRANT.ID.eq(targetGrantId))
            .fetchSingle(IAM_ROLE_GRANT.STATUS));
        assertEquals(0, dsl.fetchCount(IAM_AUDIT_EVENT,
            IAM_AUDIT_EVENT.TENANT_ID.eq(TENANT_ID)
                .and(IAM_AUDIT_EVENT.TARGET_TYPE.eq("ROLE_GRANTS"))
                .and(IAM_AUDIT_EVENT.TARGET_REF.eq(Long.toString(targetRoleId)))));
        assertEquals(0, dsl.fetchCount(IAM_PERMISSION_CHANGE_OUTBOX,
            IAM_PERMISSION_CHANGE_OUTBOX.TENANT_ID.eq(TENANT_ID)
                .and(IAM_PERMISSION_CHANGE_OUTBOX.AGGREGATE_TYPE.eq("ROLE_GRANTS"))
                .and(IAM_PERMISSION_CHANGE_OUTBOX.AGGREGATE_REF.eq(Long.toString(targetRoleId)))));
    }

    private static void awaitLockWait(int processId) throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            var activity = dsl.fetchOne(
                "select wait_event_type from pg_stat_activity where pid = ?", processId);
            if (activity != null && "Lock".equals(activity.get(0, String.class))) {
                return;
            }
            Thread.sleep(25L);
        }
        throw new AssertionError("Role grant replacement did not wait for the tenant lock");
    }

    private static void awaitDatabaseTime(OffsetDateTime boundary) throws InterruptedException {
        for (int attempt = 0; attempt < 200; attempt++) {
            if (!databaseTime().isBefore(boundary)) {
                return;
            }
            Thread.sleep(25L);
        }
        throw new AssertionError("Database time did not cross the grant expiration boundary");
    }

    private static OffsetDateTime databaseTime() {
        var statementTimestamp = DSL.field("statement_timestamp()", OffsetDateTime.class);
        return dsl.select(statementTimestamp).fetchSingle(statementTimestamp);
    }

    private static void seedAuthorizationFixture() {
        dsl.insertInto(IAM_TENANT)
            .set(IAM_TENANT.ID, TENANT_ID)
            .set(IAM_TENANT.TENANT_CODE, "tx-reauth-test")
            .set(IAM_TENANT.TENANT_NAME, "Transaction Reauthorization Test")
            .set(IAM_TENANT.TENANT_TYPE, "PLATFORM")
            .set(IAM_TENANT.STATUS, "ACTIVE")
            .execute();
        dsl.insertInto(IAM_USER)
            .set(IAM_USER.ID, USER_ID)
            .set(IAM_USER.IDP_ISSUER, "test")
            .set(IAM_USER.IDP_SUBJECT, "tx-reauth-user")
            .set(IAM_USER.DISPLAY_NAME, "Transaction Reauthorization User")
            .set(IAM_USER.STATUS, "ACTIVE")
            .execute();
        dsl.insertInto(IAM_MEMBERSHIP)
            .set(IAM_MEMBERSHIP.ID, MEMBERSHIP_ID)
            .set(IAM_MEMBERSHIP.TENANT_ID, TENANT_ID)
            .set(IAM_MEMBERSHIP.USER_ID, USER_ID)
            .set(IAM_MEMBERSHIP.STATUS, "ACTIVE")
            .set(IAM_MEMBERSHIP.PERMISSION_VERSION, PERMISSION_VERSION)
            .set(IAM_MEMBERSHIP.SESSION_VERSION, SESSION_VERSION)
            .execute();
        dsl.insertInto(IAM_AUTHENTICATION_CREDENTIAL)
            .set(IAM_AUTHENTICATION_CREDENTIAL.USER_ID, USER_ID)
            .set(IAM_AUTHENTICATION_CREDENTIAL.USERNAME, "tx-reauth-user")
            .set(IAM_AUTHENTICATION_CREDENTIAL.PASSWORD_HASH, TEST_PASSWORD_HASH)
            .set(IAM_AUTHENTICATION_CREDENTIAL.STATUS, "ACTIVE")
            .execute();

        dsl.insertInto(IAM_ROLE)
            .set(IAM_ROLE.ID, SYSTEM_ROLE_ID)
            .set(IAM_ROLE.TENANT_ID, TENANT_ID)
            .set(IAM_ROLE.ROLE_CODE, "tx-reauth-system")
            .set(IAM_ROLE.ROLE_NAME, "Transaction Reauthorization System Role")
            .set(IAM_ROLE.APPLICABLE_TENANT_TYPE, "PLATFORM")
            .set(IAM_ROLE.ASSIGNABLE, false)
            .set(IAM_ROLE.SYSTEM_ROLE, true)
            .set(IAM_ROLE.STATUS, "ACTIVE")
            .execute();
        insertTargetRole(EXPIRY_TARGET_ROLE_ID, "tx-reauth-expiry", "Expiry Target Role");
        insertTargetRole(
            MISSING_PERMISSION_TARGET_ROLE_ID,
            "tx-reauth-missing-permission",
            "Missing Permission Target Role");
        dsl.insertInto(IAM_MEMBERSHIP_ROLE)
            .set(IAM_MEMBERSHIP_ROLE.TENANT_ID, TENANT_ID)
            .set(IAM_MEMBERSHIP_ROLE.MEMBERSHIP_ID, MEMBERSHIP_ID)
            .set(IAM_MEMBERSHIP_ROLE.ROLE_ID, SYSTEM_ROLE_ID)
            .set(IAM_MEMBERSHIP_ROLE.ASSIGNED_BY, MEMBERSHIP_ID)
            .execute();

        long roleViewPermissionId = permissionId("role:view");
        long grantUpdatePermissionId = permissionId("role:grant-update");
        long userViewPermissionId = permissionId("user:view");
        insertGrant(ROLE_VIEW_GRANT_ID, SYSTEM_ROLE_ID, roleViewPermissionId, "tx-role-view");
        insertGrant(
            GRANT_UPDATE_GRANT_ID,
            SYSTEM_ROLE_ID,
            grantUpdatePermissionId,
            "tx-role-grant-update");
        insertGrant(
            EXPIRY_TARGET_GRANT_ID,
            EXPIRY_TARGET_ROLE_ID,
            userViewPermissionId,
            "tx-expiry-target-user-view");
        insertGrant(
            MISSING_PERMISSION_TARGET_GRANT_ID,
            MISSING_PERMISSION_TARGET_ROLE_ID,
            userViewPermissionId,
            "tx-missing-target-user-view");
    }

    private static void insertTargetRole(long roleId, String code, String name) {
        dsl.insertInto(IAM_ROLE)
            .set(IAM_ROLE.ID, roleId)
            .set(IAM_ROLE.TENANT_ID, TENANT_ID)
            .set(IAM_ROLE.ROLE_CODE, code)
            .set(IAM_ROLE.ROLE_NAME, name)
            .set(IAM_ROLE.APPLICABLE_TENANT_TYPE, "PLATFORM")
            .set(IAM_ROLE.ASSIGNABLE, true)
            .set(IAM_ROLE.SYSTEM_ROLE, false)
            .set(IAM_ROLE.STATUS, "ACTIVE")
            .execute();
    }

    private static long permissionId(String code) {
        return dsl.select(IAM_PERMISSION.ID)
            .from(IAM_PERMISSION)
            .where(IAM_PERMISSION.PERMISSION_CODE.eq(code))
            .fetchSingle(IAM_PERMISSION.ID);
    }

    private static void insertGrant(long grantId, long roleId, long permissionId, String grantKey) {
        dsl.insertInto(IAM_ROLE_GRANT)
            .set(IAM_ROLE_GRANT.ID, grantId)
            .set(IAM_ROLE_GRANT.TENANT_ID, TENANT_ID)
            .set(IAM_ROLE_GRANT.ROLE_ID, roleId)
            .set(IAM_ROLE_GRANT.PERMISSION_ID, permissionId)
            .set(IAM_ROLE_GRANT.GRANT_KEY, grantKey)
            .set(IAM_ROLE_GRANT.STATUS, "ACTIVE")
            .set(IAM_ROLE_GRANT.CREATED_BY, MEMBERSHIP_ID)
            .set(IAM_ROLE_GRANT.UPDATED_BY, MEMBERSHIP_ID)
            .execute();
        dsl.insertInto(IAM_GRANT_DIMENSION)
            .set(IAM_GRANT_DIMENSION.ID, grantId + 100L)
            .set(IAM_GRANT_DIMENSION.GRANT_ID, grantId)
            .set(IAM_GRANT_DIMENSION.DIMENSION_CODE, "TENANT")
            .set(IAM_GRANT_DIMENSION.SCOPE_MODE, "TENANT_ALL")
            .execute();
    }
}
