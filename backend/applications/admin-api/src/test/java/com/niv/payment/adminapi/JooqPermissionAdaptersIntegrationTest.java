package com.niv.payment.adminapi;

import com.niv.payment.permission.domain.AdministrationActor;
import com.niv.payment.permission.domain.CrossTenantMode;
import com.niv.payment.permission.domain.PermissionCode;
import com.niv.payment.permission.domain.RiskLevel;
import com.niv.payment.permission.domain.ScopeDimension;
import com.niv.payment.permission.domain.ScopeMode;
import com.niv.payment.permission.persistence.repository.JooqIdentityQueryRepository;
import com.niv.payment.permission.persistence.repository.JooqMembershipSessionVersionRepository;
import com.niv.payment.permission.persistence.repository.JooqMembershipVersionRepository;
import com.niv.payment.permission.persistence.repository.JooqPermissionCatalogRepository;
import com.niv.payment.permission.persistence.repository.JooqPermissionGrantRepository;
import com.niv.payment.permission.persistence.repository.JooqRoleAdministrationRepository;
import com.niv.payment.permission.persistence.repository.JooqRoleGrantAdministrationRepository;
import com.niv.payment.permission.port.InvalidAuthorizationSubjectException;
import com.niv.payment.permission.port.StalePermissionVersionException;
import com.niv.payment.permission.service.IdentityModels;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.ExecuteListener;
import org.jooq.JSONB;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DefaultConfiguration;
import org.jooq.impl.DefaultExecuteListenerProvider;
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
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_AUTHENTICATION_CREDENTIAL;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_GRANT_DIMENSION;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_GRANT_TARGET;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_MEMBERSHIP;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_MEMBERSHIP_ROLE;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_MENU;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_PERMISSION;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_ROLE;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_ROLE_GRANT;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_TENANT;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_USER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
class JooqPermissionAdaptersIntegrationTest {
    private static final long TENANT_ID = 8_910_000L;
    private static final long USER_ID = 8_910_001L;
    private static final long MEMBERSHIP_ID = 8_910_002L;
    private static final long ROLE_ID = 8_910_003L;
    private static final long PERMISSION_ID = 8_910_004L;
    private static final long ACTIVE_GRANT_ID = 8_910_005L;
    private static final long FUTURE_GRANT_ID = 8_910_006L;
    private static final long MARKET_DIMENSION_ID = 8_910_007L;
    private static final long CHANNEL_DIMENSION_ID = 8_910_008L;
    private static final long MENU_ID = 8_910_009L;
    private static final String TEST_PASSWORD_HASH =
        "$2a$12$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";
    private static final OffsetDateTime ACTIVE_UNTIL = OffsetDateTime.of(
        2035, 1, 1, 2, 0, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime FUTURE_FROM = OffsetDateTime.of(
        2035, 1, 1, 1, 0, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime FUTURE_UNTIL = OffsetDateTime.of(
        2035, 1, 1, 3, 0, 0, 0, ZoneOffset.UTC);

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
        seedIdentityAndGrants();
    }

    @AfterAll
    static void closeConnection() throws Exception {
        if (connection != null) {
            connection.close();
        }
    }

    @Test
    void membershipAndSessionVersionsPreserveFailClosedIdentityTuple() {
        var permissionVersions = new JooqMembershipVersionRepository(dsl);
        var sessionVersions = new JooqMembershipSessionVersionRepository(dsl);
        var grants = new JooqPermissionGrantRepository(dsl);

        assertEquals(17L, permissionVersions.findPermissionVersion(TENANT_ID, MEMBERSHIP_ID));
        var versions = sessionVersions.findActiveVersions(TENANT_ID, MEMBERSHIP_ID, USER_ID)
            .orElseThrow();
        assertEquals(17L, versions.permissionVersion());
        assertEquals(23L, versions.sessionVersion());
        assertFalse(sessionVersions.findActiveVersions(TENANT_ID, MEMBERSHIP_ID, USER_ID + 1)
            .isPresent());

        try {
            dsl.update(IAM_TENANT).set(IAM_TENANT.STATUS, "DISABLED")
                .where(IAM_TENANT.ID.eq(TENANT_ID)).execute();
            assertTrue(sessionVersions.findActiveVersions(TENANT_ID, MEMBERSHIP_ID, USER_ID).isEmpty());
            assertAuthorizationSubjectInvalid(permissionVersions, grants);
        } finally {
            dsl.update(IAM_TENANT).set(IAM_TENANT.STATUS, "ACTIVE")
                .where(IAM_TENANT.ID.eq(TENANT_ID)).execute();
        }

        try {
            dsl.update(IAM_USER).set(IAM_USER.STATUS, "DISABLED")
                .where(IAM_USER.ID.eq(USER_ID)).execute();
            assertTrue(sessionVersions.findActiveVersions(TENANT_ID, MEMBERSHIP_ID, USER_ID).isEmpty());
            assertAuthorizationSubjectInvalid(permissionVersions, grants);
        } finally {
            dsl.update(IAM_USER).set(IAM_USER.STATUS, "ACTIVE")
                .where(IAM_USER.ID.eq(USER_ID)).execute();
        }

        try {
            dsl.update(IAM_AUTHENTICATION_CREDENTIAL).set(IAM_AUTHENTICATION_CREDENTIAL.STATUS, "LOCKED")
                .where(IAM_AUTHENTICATION_CREDENTIAL.USER_ID.eq(USER_ID)).execute();
            assertTrue(sessionVersions.findActiveVersions(TENANT_ID, MEMBERSHIP_ID, USER_ID).isEmpty());
            assertAuthorizationSubjectInvalid(permissionVersions, grants);
        } finally {
            dsl.update(IAM_AUTHENTICATION_CREDENTIAL).set(IAM_AUTHENTICATION_CREDENTIAL.STATUS, "ACTIVE")
                .where(IAM_AUTHENTICATION_CREDENTIAL.USER_ID.eq(USER_ID)).execute();
        }

        String passwordHash = dsl.select(IAM_AUTHENTICATION_CREDENTIAL.PASSWORD_HASH)
            .from(IAM_AUTHENTICATION_CREDENTIAL)
            .where(IAM_AUTHENTICATION_CREDENTIAL.USER_ID.eq(USER_ID))
            .fetchSingle(IAM_AUTHENTICATION_CREDENTIAL.PASSWORD_HASH);
        try {
            dsl.update(IAM_AUTHENTICATION_CREDENTIAL)
                .setNull(IAM_AUTHENTICATION_CREDENTIAL.PASSWORD_HASH)
                .where(IAM_AUTHENTICATION_CREDENTIAL.USER_ID.eq(USER_ID)).execute();
            assertTrue(sessionVersions.findActiveVersions(TENANT_ID, MEMBERSHIP_ID, USER_ID).isEmpty());
            assertAuthorizationSubjectInvalid(permissionVersions, grants);
        } finally {
            dsl.update(IAM_AUTHENTICATION_CREDENTIAL)
                .set(IAM_AUTHENTICATION_CREDENTIAL.PASSWORD_HASH, passwordHash)
                .where(IAM_AUTHENTICATION_CREDENTIAL.USER_ID.eq(USER_ID)).execute();
        }

        try {
            dsl.update(IAM_MEMBERSHIP).set(IAM_MEMBERSHIP.STATUS, "DISABLED")
                .where(IAM_MEMBERSHIP.ID.eq(MEMBERSHIP_ID)).execute();
            assertTrue(sessionVersions.findActiveVersions(TENANT_ID, MEMBERSHIP_ID, USER_ID).isEmpty());
            assertAuthorizationSubjectInvalid(permissionVersions, grants);
        } finally {
            dsl.update(IAM_MEMBERSHIP).set(IAM_MEMBERSHIP.STATUS, "ACTIVE")
                .where(IAM_MEMBERSHIP.ID.eq(MEMBERSHIP_ID)).execute();
        }
    }

    @Test
    void permissionCatalogMapsPostgresArraysWithoutStringFlattening() {
        var repository = new JooqPermissionCatalogRepository(dsl);

        var definition = repository.require(PermissionCode.of("jooq-test:read"));

        assertEquals(RiskLevel.NORMAL, definition.riskLevel());
        assertEquals(CrossTenantMode.RELATED_PARTY_READ, definition.crossTenantMode());
        assertEquals(Set.of(ScopeDimension.MARKET, ScopeDimension.CHANNEL),
            definition.requiredDimensions());
        assertTrue(definition.requiresApproval());
        assertThrows(IllegalArgumentException.class,
            () -> repository.require(PermissionCode.of("jooq-test:missing")));
    }

    @Test
    void permissionGrantsMapAtomicScopesAndTheNextDatabaseTimeBoundary() {
        var repository = new JooqPermissionGrantRepository(dsl);

        var snapshot = repository.load(TENANT_ID, MEMBERSHIP_ID, 17L);

        assertEquals(1, snapshot.grants().size());
        assertEquals(FUTURE_FROM.toInstant(), snapshot.refreshAfter());
        var grant = snapshot.grants().getFirst();
        assertEquals(ACTIVE_GRANT_ID, grant.id());
        assertEquals(Set.of(ScopeDimension.MARKET, ScopeDimension.CHANNEL), grant.requiredDimensions());
        assertEquals(2, grant.scopes().size());
        assertTrue(grant.scopes().stream().anyMatch(scope -> scope.dimension() == ScopeDimension.MARKET
            && scope.mode() == ScopeMode.SPECIFIED && scope.targets().equals(Set.of("PK"))));
        assertTrue(grant.scopes().stream().anyMatch(scope -> scope.dimension() == ScopeDimension.CHANNEL
            && scope.mode() == ScopeMode.SPECIFIED && scope.targets().equals(Set.of("card"))));
    }

    @Test
    void databaseRejectsDimensionModePairsOutsideTheDomainMatrix() {
        assertEquals("SPECIFIED",
            dsl.select(IAM_GRANT_DIMENSION.SCOPE_MODE)
                .from(IAM_GRANT_DIMENSION)
                .where(IAM_GRANT_DIMENSION.ID.eq(CHANNEL_DIMENSION_ID))
                .fetchSingle(IAM_GRANT_DIMENSION.SCOPE_MODE));

        DataAccessException error = assertThrows(DataAccessException.class, () ->
            dsl.insertInto(IAM_GRANT_DIMENSION,
                    IAM_GRANT_DIMENSION.ID,
                    IAM_GRANT_DIMENSION.GRANT_ID,
                    IAM_GRANT_DIMENSION.DIMENSION_CODE,
                    IAM_GRANT_DIMENSION.SCOPE_MODE)
                .values(8_910_012L, ACTIVE_GRANT_ID, "TENANT", "SELF")
                .execute());

        assertEquals("23514", error.sqlState());
        assertFalse(dsl.fetchExists(dsl.selectOne()
            .from(IAM_GRANT_DIMENSION)
            .where(IAM_GRANT_DIMENSION.ID.eq(8_910_012L))));
    }

    @Test
    void grantSnapshotAndRefreshBoundaryUseOneDatabaseStatement() {
        AtomicInteger statements = new AtomicInteger();
        var configuration = new DefaultConfiguration();
        configuration.set(connection);
        configuration.set(SQLDialect.POSTGRES);
        configuration.set(new DefaultExecuteListenerProvider(
            ExecuteListener.onExecuteStart(context -> statements.incrementAndGet())));
        var repository = new JooqPermissionGrantRepository(DSL.using(configuration));

        var snapshot = repository.load(TENANT_ID, MEMBERSHIP_ID, 17L);

        assertEquals(1, statements.get());
        assertEquals(1, snapshot.grants().size());
        assertEquals(FUTURE_FROM.toInstant(), snapshot.refreshAfter());
    }

    @Test
    void roleGrantAdministrationBatchesTargetInspection() {
        AtomicInteger statements = new AtomicInteger();
        var configuration = new DefaultConfiguration();
        configuration.set(connection);
        configuration.set(SQLDialect.POSTGRES);
        configuration.set(new DefaultExecuteListenerProvider(
            ExecuteListener.onExecuteStart(context -> statements.incrementAndGet())));
        var repository = new JooqRoleGrantAdministrationRepository(
            DSL.using(configuration), () -> "batched-role-grant-read-test");

        try {
            dsl.update(IAM_ROLE)
                .set(IAM_ROLE.SYSTEM_ROLE, true)
                .set(IAM_ROLE.ASSIGNABLE, false)
                .where(IAM_ROLE.TENANT_ID.eq(TENANT_ID).and(IAM_ROLE.ID.eq(ROLE_ID)))
                .execute();

            var roleGrants = repository.findRoleGrants(TENANT_ID,
                new AdministrationActor(MEMBERSHIP_ID, USER_ID, 17L, 23L), ROLE_ID);

            assertEquals(6, statements.get());
            assertFalse(roleGrants.editable());
        } finally {
            dsl.update(IAM_ROLE)
                .set(IAM_ROLE.SYSTEM_ROLE, false)
                .set(IAM_ROLE.ASSIGNABLE, true)
                .where(IAM_ROLE.TENANT_ID.eq(TENANT_ID).and(IAM_ROLE.ID.eq(ROLE_ID)))
                .execute();
        }
    }

    @Test
    void longTransactionUsesStatementTimeAndDoesNotReviveAnExpiredGrant() throws Exception {
        try (Connection transactionConnection = DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            transactionConnection.setAutoCommit(false);
            DSLContext transactionDsl = DSL.using(transactionConnection, SQLDialect.POSTGRES);
            var transactionTimestamp = DSL.currentOffsetDateTime();
            OffsetDateTime transactionStartedAt = transactionDsl
                .select(transactionTimestamp)
                .fetchSingle(transactionTimestamp);
            OffsetDateTime expiresAt = transactionStartedAt.plusSeconds(1);
            transactionDsl.update(IAM_ROLE_GRANT)
                .set(IAM_ROLE_GRANT.VALID_UNTIL, expiresAt)
                .where(IAM_ROLE_GRANT.ID.eq(ACTIVE_GRANT_ID))
                .execute();

            Thread.sleep(1_200L);

            var snapshot = new JooqPermissionGrantRepository(transactionDsl)
                .load(TENANT_ID, MEMBERSHIP_ID, 17L);
            assertTrue(snapshot.grants().stream()
                .noneMatch(grant -> grant.id() == ACTIVE_GRANT_ID));
            assertEquals(FUTURE_FROM.toInstant(), snapshot.refreshAfter());
            transactionConnection.rollback();
        }
    }

    @Test
    void oversizedSnapshotDetailFailsClosedAtTheDocumentedCeiling() {
        long firstTargetId = 8_920_000L;
        int addedTargets = JooqPermissionGrantRepository.MAX_SNAPSHOT_DETAIL_ROWS;
        var targetInsert = dsl.insertInto(IAM_GRANT_TARGET,
                IAM_GRANT_TARGET.ID, IAM_GRANT_TARGET.DIMENSION_ID, IAM_GRANT_TARGET.TARGET_REF)
            .values((Long) null, (Long) null, (String) null);
        var batch = dsl.batch(targetInsert);
        for (int index = 0; index < addedTargets; index++) {
            batch.bind(firstTargetId + index, MARKET_DIMENSION_ID, "ceiling-target-" + index);
        }

        try {
            batch.execute();
            var repository = new JooqPermissionGrantRepository(dsl);
            IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> repository.load(TENANT_ID, MEMBERSHIP_ID, 17L));
            assertTrue(error.getMessage().contains(
                Integer.toString(JooqPermissionGrantRepository.MAX_SNAPSHOT_DETAIL_ROWS)));
        } finally {
            dsl.deleteFrom(IAM_GRANT_TARGET)
                .where(IAM_GRANT_TARGET.ID.ge(firstTargetId)
                    .and(IAM_GRANT_TARGET.ID.lt(firstTargetId + addedTargets)))
                .execute();
        }
    }

    @Test
    void permissionGrantLookupRejectsAStaleExpectedVersionAcrossConnections() throws Exception {
        var repository = new JooqPermissionGrantRepository(dsl);
        long observedVersion = new JooqMembershipVersionRepository(dsl)
            .findPermissionVersion(TENANT_ID, MEMBERSHIP_ID);

        try (Connection writerConnection = DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            DSLContext writer = DSL.using(writerConnection, SQLDialect.POSTGRES);
            writer.update(IAM_MEMBERSHIP)
                .set(IAM_MEMBERSHIP.PERMISSION_VERSION, observedVersion + 1)
                .where(IAM_MEMBERSHIP.TENANT_ID.eq(TENANT_ID)
                    .and(IAM_MEMBERSHIP.ID.eq(MEMBERSHIP_ID)))
                .execute();

            assertThrows(StalePermissionVersionException.class,
                () -> repository.load(TENANT_ID, MEMBERSHIP_ID, observedVersion));
            var currentSnapshot = repository.load(TENANT_ID, MEMBERSHIP_ID, observedVersion + 1);
            assertEquals(1, currentSnapshot.grants().size());
            assertEquals(ACTIVE_GRANT_ID, currentSnapshot.grants().getFirst().id());
        } finally {
            dsl.update(IAM_MEMBERSHIP)
                .set(IAM_MEMBERSHIP.PERMISSION_VERSION, observedVersion)
                .where(IAM_MEMBERSHIP.TENANT_ID.eq(TENANT_ID)
                    .and(IAM_MEMBERSHIP.ID.eq(MEMBERSHIP_ID)))
                .execute();
        }
    }

    @Test
    void administrationWriteRechecksOperatorVersionAfterWaitingForTenantLock() throws Exception {
        long observedVersion = new JooqMembershipVersionRepository(dsl)
            .findPermissionVersion(TENANT_ID, MEMBERSHIP_ID);
        long observedSessionVersion = currentSessionVersion();
        String roleName = "Must Not Survive Stale Authorization";
        try {
            Throwable failure = runAdministrationWriteAfterTenantLock(
                observedVersion, observedSessionVersion, roleName, locker ->
                locker.update(IAM_MEMBERSHIP)
                    .set(IAM_MEMBERSHIP.PERMISSION_VERSION, IAM_MEMBERSHIP.PERMISSION_VERSION.plus(1L))
                    .where(IAM_MEMBERSHIP.TENANT_ID.eq(TENANT_ID)
                        .and(IAM_MEMBERSHIP.ID.eq(MEMBERSHIP_ID)))
                    .execute());
            assertInstanceOf(StalePermissionVersionException.class, failure);
        } finally {
            dsl.update(IAM_MEMBERSHIP)
                .set(IAM_MEMBERSHIP.PERMISSION_VERSION, observedVersion)
                .where(IAM_MEMBERSHIP.TENANT_ID.eq(TENANT_ID)
                    .and(IAM_MEMBERSHIP.ID.eq(MEMBERSHIP_ID)))
                .execute();
            dsl.deleteFrom(IAM_ROLE)
                .where(IAM_ROLE.TENANT_ID.eq(TENANT_ID)
                    .and(IAM_ROLE.ROLE_NAME.eq(roleName)))
                .execute();
        }

        assertEquals(0, dsl.fetchCount(IAM_ROLE,
            IAM_ROLE.TENANT_ID.eq(TENANT_ID).and(IAM_ROLE.ROLE_NAME.eq(roleName))));
    }

    @Test
    void administrationWriteRechecksTenantStateAfterWaitingForTenantLock() throws Exception {
        long observedVersion = new JooqMembershipVersionRepository(dsl)
            .findPermissionVersion(TENANT_ID, MEMBERSHIP_ID);
        long observedSessionVersion = currentSessionVersion();
        String roleName = "Must Not Survive Disabled Tenant";
        try {
            Throwable failure = runAdministrationWriteAfterTenantLock(
                observedVersion, observedSessionVersion, roleName, locker ->
                locker.update(IAM_TENANT)
                    .set(IAM_TENANT.STATUS, "DISABLED")
                    .where(IAM_TENANT.ID.eq(TENANT_ID))
                    .execute());
            assertInstanceOf(SecurityException.class, failure);
        } finally {
            dsl.update(IAM_TENANT)
                .set(IAM_TENANT.STATUS, "ACTIVE")
                .where(IAM_TENANT.ID.eq(TENANT_ID))
                .execute();
            dsl.deleteFrom(IAM_ROLE)
                .where(IAM_ROLE.TENANT_ID.eq(TENANT_ID)
                    .and(IAM_ROLE.ROLE_NAME.eq(roleName)))
                .execute();
        }

        assertEquals(0, dsl.fetchCount(IAM_ROLE,
            IAM_ROLE.TENANT_ID.eq(TENANT_ID).and(IAM_ROLE.ROLE_NAME.eq(roleName))));
    }

    @Test
    void administrationWriteRechecksSessionVersionAfterWaitingForTenantLock() throws Exception {
        long observedPermissionVersion = new JooqMembershipVersionRepository(dsl)
            .findPermissionVersion(TENANT_ID, MEMBERSHIP_ID);
        long observedSessionVersion = currentSessionVersion();
        String roleName = "Must Not Survive Stale Session";
        try {
            Throwable failure = runAdministrationWriteAfterTenantLock(
                observedPermissionVersion, observedSessionVersion, roleName, locker ->
                    locker.update(IAM_MEMBERSHIP)
                        .set(IAM_MEMBERSHIP.SESSION_VERSION, IAM_MEMBERSHIP.SESSION_VERSION.plus(1L))
                        .where(IAM_MEMBERSHIP.TENANT_ID.eq(TENANT_ID)
                            .and(IAM_MEMBERSHIP.ID.eq(MEMBERSHIP_ID)))
                        .execute());
            assertInstanceOf(InvalidAuthorizationSubjectException.class, failure);
        } finally {
            dsl.update(IAM_MEMBERSHIP)
                .set(IAM_MEMBERSHIP.SESSION_VERSION, observedSessionVersion)
                .where(IAM_MEMBERSHIP.TENANT_ID.eq(TENANT_ID)
                    .and(IAM_MEMBERSHIP.ID.eq(MEMBERSHIP_ID)))
                .execute();
            dsl.deleteFrom(IAM_ROLE)
                .where(IAM_ROLE.TENANT_ID.eq(TENANT_ID)
                    .and(IAM_ROLE.ROLE_NAME.eq(roleName)))
                .execute();
        }

        assertEquals(0, dsl.fetchCount(IAM_ROLE,
            IAM_ROLE.TENANT_ID.eq(TENANT_ID).and(IAM_ROLE.ROLE_NAME.eq(roleName))));
    }

    @Test
    void administrationWriteHoldingMembershipLockLinearizesBeforeSessionRevocation() throws Exception {
        long observedPermissionVersion = new JooqMembershipVersionRepository(dsl)
            .findPermissionVersion(TENANT_ID, MEMBERSHIP_ID);
        long observedSessionVersion = currentSessionVersion();
        String roleName = "Write Before Session Revocation";
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> revocation = null;

        try (Connection administrationConnection = DriverManager.getConnection(
                 POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Connection revocationConnection = DriverManager.getConnection(
                 POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            administrationConnection.setAutoCommit(false);
            revocationConnection.setAutoCommit(false);
            DSLContext administration = DSL.using(administrationConnection, SQLDialect.POSTGRES);
            DSLContext revoker = DSL.using(revocationConnection, SQLDialect.POSTGRES);
            int revokerPid = revoker.select(DSL.field("pg_backend_pid()", Integer.class))
                .fetchSingle()
                .value1();

            var repository = new JooqRoleAdministrationRepository(
                administration, new JooqIdentityQueryRepository(administration),
                () -> "write-before-revocation-test");
            repository.createRole(TENANT_ID,
                new AdministrationActor(
                    MEMBERSHIP_ID, USER_ID, observedPermissionVersion, observedSessionVersion),
                new IdentityModels.RoleCommand(roleName, List.of(), 1, null));

            revocation = executor.submit(() -> {
                revoker.update(IAM_MEMBERSHIP)
                    .set(IAM_MEMBERSHIP.SESSION_VERSION, IAM_MEMBERSHIP.SESSION_VERSION.plus(1L))
                    .where(IAM_MEMBERSHIP.TENANT_ID.eq(TENANT_ID)
                        .and(IAM_MEMBERSHIP.ID.eq(MEMBERSHIP_ID)))
                    .execute();
                revocationConnection.commit();
                return null;
            });
            awaitLockWait(revokerPid);

            administrationConnection.commit();
            revocation.get(5, TimeUnit.SECONDS);

            assertEquals(1, dsl.fetchCount(IAM_ROLE,
                IAM_ROLE.TENANT_ID.eq(TENANT_ID).and(IAM_ROLE.ROLE_NAME.eq(roleName))));
            assertEquals(observedSessionVersion + 1, currentSessionVersion());
        } finally {
            if (revocation != null && !revocation.isDone()) {
                revocation.cancel(true);
            }
            executor.shutdownNow();
            dsl.update(IAM_MEMBERSHIP)
                .set(IAM_MEMBERSHIP.SESSION_VERSION, observedSessionVersion)
                .where(IAM_MEMBERSHIP.TENANT_ID.eq(TENANT_ID)
                    .and(IAM_MEMBERSHIP.ID.eq(MEMBERSHIP_ID)))
                .execute();
            dsl.deleteFrom(IAM_ROLE)
                .where(IAM_ROLE.TENANT_ID.eq(TENANT_ID)
                    .and(IAM_ROLE.ROLE_NAME.eq(roleName)))
                .execute();
        }
    }

    @Test
    void administrationWritesRequireTheActiveOperatorTuple() {
        long observedVersion = new JooqMembershipVersionRepository(dsl)
            .findPermissionVersion(TENANT_ID, MEMBERSHIP_ID);
        long observedSessionVersion = currentSessionVersion();
        String passwordHash = dsl.select(IAM_AUTHENTICATION_CREDENTIAL.PASSWORD_HASH)
            .from(IAM_AUTHENTICATION_CREDENTIAL)
            .where(IAM_AUTHENTICATION_CREDENTIAL.USER_ID.eq(USER_ID))
            .fetchSingle(IAM_AUTHENTICATION_CREDENTIAL.PASSWORD_HASH);
        var actor = new AdministrationActor(
            MEMBERSHIP_ID, USER_ID, observedVersion, observedSessionVersion);
        String roleName = "Must Not Survive Inactive Operator";
        var repository = new JooqRoleAdministrationRepository(
            dsl, new JooqIdentityQueryRepository(dsl), () -> "inactive-operator-test");

        try {
            dsl.update(IAM_MEMBERSHIP).set(IAM_MEMBERSHIP.STATUS, "DISABLED")
                .where(IAM_MEMBERSHIP.ID.eq(MEMBERSHIP_ID)).execute();
            assertThrows(InvalidAuthorizationSubjectException.class, () -> repository.createRole(TENANT_ID, actor,
                new IdentityModels.RoleCommand(roleName, List.of(), 1, null)));
            dsl.update(IAM_MEMBERSHIP).set(IAM_MEMBERSHIP.STATUS, "ACTIVE")
                .where(IAM_MEMBERSHIP.ID.eq(MEMBERSHIP_ID)).execute();
            assertThrows(InvalidAuthorizationSubjectException.class, () -> repository.createRole(TENANT_ID,
                new AdministrationActor(
                    MEMBERSHIP_ID, USER_ID + 1, observedVersion, observedSessionVersion),
                new IdentityModels.RoleCommand(roleName, List.of(), 1, null)));

            dsl.update(IAM_USER).set(IAM_USER.STATUS, "DISABLED")
                .where(IAM_USER.ID.eq(USER_ID)).execute();
            assertThrows(InvalidAuthorizationSubjectException.class, () -> repository.createRole(TENANT_ID, actor,
                new IdentityModels.RoleCommand(roleName, List.of(), 1, null)));
            dsl.update(IAM_USER).set(IAM_USER.STATUS, "ACTIVE")
                .where(IAM_USER.ID.eq(USER_ID)).execute();

            dsl.update(IAM_AUTHENTICATION_CREDENTIAL)
                .set(IAM_AUTHENTICATION_CREDENTIAL.STATUS, "LOCKED")
                .where(IAM_AUTHENTICATION_CREDENTIAL.USER_ID.eq(USER_ID)).execute();
            assertThrows(InvalidAuthorizationSubjectException.class, () -> repository.createRole(TENANT_ID, actor,
                new IdentityModels.RoleCommand(roleName, List.of(), 1, null)));
            dsl.update(IAM_AUTHENTICATION_CREDENTIAL)
                .set(IAM_AUTHENTICATION_CREDENTIAL.STATUS, "ACTIVE")
                .setNull(IAM_AUTHENTICATION_CREDENTIAL.PASSWORD_HASH)
                .where(IAM_AUTHENTICATION_CREDENTIAL.USER_ID.eq(USER_ID)).execute();
            assertThrows(InvalidAuthorizationSubjectException.class, () -> repository.createRole(TENANT_ID, actor,
                new IdentityModels.RoleCommand(roleName, List.of(), 1, null)));
        } finally {
            dsl.update(IAM_MEMBERSHIP).set(IAM_MEMBERSHIP.STATUS, "ACTIVE")
                .where(IAM_MEMBERSHIP.ID.eq(MEMBERSHIP_ID)).execute();
            dsl.update(IAM_USER).set(IAM_USER.STATUS, "ACTIVE")
                .where(IAM_USER.ID.eq(USER_ID)).execute();
            dsl.update(IAM_AUTHENTICATION_CREDENTIAL)
                .set(IAM_AUTHENTICATION_CREDENTIAL.STATUS, "ACTIVE")
                .set(IAM_AUTHENTICATION_CREDENTIAL.PASSWORD_HASH, passwordHash)
                .where(IAM_AUTHENTICATION_CREDENTIAL.USER_ID.eq(USER_ID)).execute();
            dsl.deleteFrom(IAM_ROLE)
                .where(IAM_ROLE.TENANT_ID.eq(TENANT_ID)
                    .and(IAM_ROLE.ROLE_NAME.eq(roleName)))
                .execute();
        }

        assertEquals(0, dsl.fetchCount(IAM_ROLE,
            IAM_ROLE.TENANT_ID.eq(TENANT_ID).and(IAM_ROLE.ROLE_NAME.eq(roleName))));
    }

    @Test
    void temporalBoundaryHandlesFutureOnlyAndEmptyGrantSets() {
        var repository = new JooqPermissionGrantRepository(dsl);

        try {
            dsl.update(IAM_ROLE_GRANT)
                .set(IAM_ROLE_GRANT.STATUS, "DISABLED")
                .where(IAM_ROLE_GRANT.ID.eq(FUTURE_GRANT_ID))
                .execute();

            var currentOnly = repository.load(TENANT_ID, MEMBERSHIP_ID, 17L);
            assertEquals(1, currentOnly.grants().size());
            assertEquals(ACTIVE_UNTIL.toInstant(), currentOnly.refreshAfter());

            dsl.update(IAM_ROLE_GRANT)
                .set(IAM_ROLE_GRANT.STATUS, "ACTIVE")
                .where(IAM_ROLE_GRANT.ID.eq(FUTURE_GRANT_ID))
                .execute();
            dsl.update(IAM_ROLE_GRANT)
                .set(IAM_ROLE_GRANT.STATUS, "DISABLED")
                .where(IAM_ROLE_GRANT.ID.eq(ACTIVE_GRANT_ID))
                .execute();

            var futureOnly = repository.load(TENANT_ID, MEMBERSHIP_ID, 17L);
            assertTrue(futureOnly.grants().isEmpty());
            assertEquals(FUTURE_FROM.toInstant(), futureOnly.refreshAfter());

            dsl.update(IAM_ROLE_GRANT)
                .set(IAM_ROLE_GRANT.STATUS, "DISABLED")
                .where(IAM_ROLE_GRANT.ID.eq(FUTURE_GRANT_ID))
                .execute();

            var empty = repository.load(TENANT_ID, MEMBERSHIP_ID, 17L);
            assertTrue(empty.grants().isEmpty());
            assertNull(empty.refreshAfter());
        } finally {
            dsl.update(IAM_ROLE_GRANT)
                .set(IAM_ROLE_GRANT.STATUS, "ACTIVE")
                .where(IAM_ROLE_GRANT.ID.in(ACTIVE_GRANT_ID, FUTURE_GRANT_ID))
                .execute();
        }
    }

    @Test
    void disabledAuthorizationRowsNeverLeakGrantsOrTimeBoundaries() {
        var repository = new JooqPermissionGrantRepository(dsl);

        try {
            dsl.update(IAM_ROLE)
                .set(IAM_ROLE.STATUS, "DISABLED")
                .where(IAM_ROLE.ID.eq(ROLE_ID).and(IAM_ROLE.TENANT_ID.eq(TENANT_ID)))
                .execute();
            assertDisabledSnapshot(repository.load(TENANT_ID, MEMBERSHIP_ID, 17L));
        } finally {
            dsl.update(IAM_ROLE)
                .set(IAM_ROLE.STATUS, "ACTIVE")
                .where(IAM_ROLE.ID.eq(ROLE_ID).and(IAM_ROLE.TENANT_ID.eq(TENANT_ID)))
                .execute();
        }

        try {
            dsl.update(IAM_PERMISSION)
                .set(IAM_PERMISSION.STATUS, "DISABLED")
                .where(IAM_PERMISSION.ID.eq(PERMISSION_ID))
                .execute();
            assertDisabledSnapshot(repository.load(TENANT_ID, MEMBERSHIP_ID, 17L));
        } finally {
            dsl.update(IAM_PERMISSION)
                .set(IAM_PERMISSION.STATUS, "ACTIVE")
                .where(IAM_PERMISSION.ID.eq(PERMISSION_ID))
                .execute();
        }
    }

    @Test
    void tenantAndMembershipTupleCannotReadAnotherGrantSet() {
        var repository = new JooqPermissionGrantRepository(dsl);

        assertThrows(InvalidAuthorizationSubjectException.class,
            () -> repository.load(TENANT_ID + 1, MEMBERSHIP_ID, 17L));
        assertThrows(InvalidAuthorizationSubjectException.class,
            () -> repository.load(TENANT_ID, MEMBERSHIP_ID + 1, 17L));
    }

    @Test
    void generatedJsonbFieldRoundTripsWithoutTextCasts() {
        JSONB metadata = JSONB.valueOf("{\"title\":\"system.test\",\"order\":7}");
        dsl.insertInto(IAM_MENU,
                IAM_MENU.ID, IAM_MENU.TENANT_ID, IAM_MENU.MENU_TYPE, IAM_MENU.MENU_NAME,
                IAM_MENU.ROUTE_PATH, IAM_MENU.COMPONENT_PATH, IAM_MENU.SORT_ORDER,
                IAM_MENU.STATUS, IAM_MENU.META_JSON)
            .values(MENU_ID, TENANT_ID, "PAGE", "jOOQ test", "/jooq-test", "/jooq-test/index",
                990, "ACTIVE", metadata)
            .execute();

        JSONB stored = dsl.select(IAM_MENU.META_JSON)
            .from(IAM_MENU)
            .where(IAM_MENU.ID.eq(MENU_ID).and(IAM_MENU.TENANT_ID.eq(TENANT_ID)))
            .fetchSingle(IAM_MENU.META_JSON);

        assertEquals(metadata, stored);
    }

    private static void seedIdentityAndGrants() {
        dsl.insertInto(IAM_TENANT,
                IAM_TENANT.ID, IAM_TENANT.TENANT_CODE, IAM_TENANT.TENANT_NAME,
                IAM_TENANT.TENANT_TYPE, IAM_TENANT.STATUS)
            .values(TENANT_ID, "jooq-test", "jOOQ Test Tenant", "PLATFORM", "ACTIVE")
            .execute();
        dsl.insertInto(IAM_USER,
                IAM_USER.ID, IAM_USER.IDP_ISSUER, IAM_USER.IDP_SUBJECT,
                IAM_USER.DISPLAY_NAME, IAM_USER.STATUS)
            .values(USER_ID, "integration-test", "jooq-user", "jOOQ User", "ACTIVE")
            .execute();
        dsl.insertInto(IAM_AUTHENTICATION_CREDENTIAL,
                IAM_AUTHENTICATION_CREDENTIAL.USER_ID, IAM_AUTHENTICATION_CREDENTIAL.USERNAME,
                IAM_AUTHENTICATION_CREDENTIAL.PASSWORD_HASH, IAM_AUTHENTICATION_CREDENTIAL.STATUS)
            .values(USER_ID, "jooq-user", TEST_PASSWORD_HASH, "ACTIVE")
            .execute();
        dsl.insertInto(IAM_MEMBERSHIP,
                IAM_MEMBERSHIP.ID, IAM_MEMBERSHIP.TENANT_ID, IAM_MEMBERSHIP.USER_ID,
                IAM_MEMBERSHIP.STATUS, IAM_MEMBERSHIP.PERMISSION_VERSION, IAM_MEMBERSHIP.SESSION_VERSION)
            .values(MEMBERSHIP_ID, TENANT_ID, USER_ID, "ACTIVE", 17L, 23L)
            .execute();
        dsl.insertInto(IAM_ROLE,
                IAM_ROLE.ID, IAM_ROLE.TENANT_ID, IAM_ROLE.ROLE_CODE, IAM_ROLE.ROLE_NAME,
                IAM_ROLE.APPLICABLE_TENANT_TYPE, IAM_ROLE.ASSIGNABLE, IAM_ROLE.SYSTEM_ROLE, IAM_ROLE.STATUS)
            .values(ROLE_ID, TENANT_ID, "jooq-reader", "jOOQ Reader", "PLATFORM", true, false, "ACTIVE")
            .execute();
        dsl.insertInto(IAM_MEMBERSHIP_ROLE,
                IAM_MEMBERSHIP_ROLE.TENANT_ID, IAM_MEMBERSHIP_ROLE.MEMBERSHIP_ID,
                IAM_MEMBERSHIP_ROLE.ROLE_ID, IAM_MEMBERSHIP_ROLE.ASSIGNED_BY)
            .values(TENANT_ID, MEMBERSHIP_ID, ROLE_ID, MEMBERSHIP_ID)
            .execute();
        dsl.insertInto(IAM_PERMISSION,
                IAM_PERMISSION.ID, IAM_PERMISSION.PERMISSION_CODE, IAM_PERMISSION.RESOURCE_CODE,
                IAM_PERMISSION.ACTION_CODE, IAM_PERMISSION.RISK_LEVEL, IAM_PERMISSION.REQUIRED_DIMENSIONS,
                IAM_PERMISSION.REQUIRES_STEP_UP, IAM_PERMISSION.REQUIRES_APPROVAL,
                IAM_PERMISSION.STATUS, IAM_PERMISSION.CROSS_TENANT_MODE)
            .values(PERMISSION_ID, "jooq-test:read", "jooq-test", "read", "NORMAL",
                new String[]{"MARKET", "CHANNEL"}, false, true, "ACTIVE", "RELATED_PARTY_READ")
            .execute();
        dsl.insertInto(IAM_ROLE_GRANT,
                IAM_ROLE_GRANT.ID, IAM_ROLE_GRANT.TENANT_ID, IAM_ROLE_GRANT.ROLE_ID,
                IAM_ROLE_GRANT.PERMISSION_ID, IAM_ROLE_GRANT.GRANT_KEY, IAM_ROLE_GRANT.STATUS,
                IAM_ROLE_GRANT.VALID_UNTIL)
            .values(ACTIVE_GRANT_ID, TENANT_ID, ROLE_ID, PERMISSION_ID, "active", "ACTIVE", ACTIVE_UNTIL)
            .execute();
        dsl.insertInto(IAM_ROLE_GRANT,
                IAM_ROLE_GRANT.ID, IAM_ROLE_GRANT.TENANT_ID, IAM_ROLE_GRANT.ROLE_ID,
                IAM_ROLE_GRANT.PERMISSION_ID, IAM_ROLE_GRANT.GRANT_KEY, IAM_ROLE_GRANT.STATUS,
                IAM_ROLE_GRANT.VALID_FROM, IAM_ROLE_GRANT.VALID_UNTIL)
            .values(FUTURE_GRANT_ID, TENANT_ID, ROLE_ID, PERMISSION_ID, "future", "ACTIVE",
                FUTURE_FROM, FUTURE_UNTIL)
            .execute();
        dsl.insertInto(IAM_GRANT_DIMENSION,
                IAM_GRANT_DIMENSION.ID, IAM_GRANT_DIMENSION.GRANT_ID,
                IAM_GRANT_DIMENSION.DIMENSION_CODE, IAM_GRANT_DIMENSION.SCOPE_MODE)
            .values(MARKET_DIMENSION_ID, ACTIVE_GRANT_ID, "MARKET", "SPECIFIED")
            .values(CHANNEL_DIMENSION_ID, ACTIVE_GRANT_ID, "CHANNEL", "SPECIFIED")
            .execute();
        dsl.insertInto(IAM_GRANT_TARGET,
                IAM_GRANT_TARGET.ID, IAM_GRANT_TARGET.DIMENSION_ID, IAM_GRANT_TARGET.TARGET_REF)
            .values(8_910_010L, MARKET_DIMENSION_ID, "PK")
            .values(8_910_011L, CHANNEL_DIMENSION_ID, "card")
            .execute();
        seedExpiredGrantHistory();
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
        throw new AssertionError("Administration write did not wait for the tenant lock");
    }

    private static Throwable runAdministrationWriteAfterTenantLock(
        long expectedPermissionVersion,
        long expectedSessionVersion,
        String roleName,
        Consumer<DSLContext> mutateWhileLocked) throws Exception {
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

            var repository = new JooqRoleAdministrationRepository(
                worker, new JooqIdentityQueryRepository(worker), () -> "stale-authorization-test");
            operation = executor.submit(() -> {
                try {
                    repository.createRole(TENANT_ID,
                        new AdministrationActor(
                            MEMBERSHIP_ID, USER_ID,
                            expectedPermissionVersion, expectedSessionVersion),
                        new IdentityModels.RoleCommand(roleName, List.of(), 1, null));
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
            });

            awaitLockWait(workerPid);
            mutateWhileLocked.accept(locker);
            lockerConnection.commit();
            return operation.get(5, TimeUnit.SECONDS);
        } finally {
            if (operation != null && !operation.isDone()) {
                operation.cancel(true);
            }
            executor.shutdownNow();
        }
    }

    private static long currentSessionVersion() {
        return dsl.select(IAM_MEMBERSHIP.SESSION_VERSION)
            .from(IAM_MEMBERSHIP)
            .where(IAM_MEMBERSHIP.TENANT_ID.eq(TENANT_ID)
                .and(IAM_MEMBERSHIP.ID.eq(MEMBERSHIP_ID)))
            .fetchSingle(IAM_MEMBERSHIP.SESSION_VERSION);
    }

    private static void seedExpiredGrantHistory() {
        OffsetDateTime validFrom = OffsetDateTime.of(2020, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime validUntil = OffsetDateTime.of(2021, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        for (int index = 0; index < 64; index++) {
            long grantId = 8_911_000L + index;
            long dimensionId = 8_912_000L + index;
            dsl.insertInto(IAM_ROLE_GRANT,
                    IAM_ROLE_GRANT.ID, IAM_ROLE_GRANT.TENANT_ID, IAM_ROLE_GRANT.ROLE_ID,
                    IAM_ROLE_GRANT.PERMISSION_ID, IAM_ROLE_GRANT.GRANT_KEY, IAM_ROLE_GRANT.STATUS,
                    IAM_ROLE_GRANT.VALID_FROM, IAM_ROLE_GRANT.VALID_UNTIL)
                .values(grantId, TENANT_ID, ROLE_ID, PERMISSION_ID, "expired-" + index, "ACTIVE",
                    validFrom, validUntil)
                .execute();
            dsl.insertInto(IAM_GRANT_DIMENSION,
                    IAM_GRANT_DIMENSION.ID, IAM_GRANT_DIMENSION.GRANT_ID,
                    IAM_GRANT_DIMENSION.DIMENSION_CODE, IAM_GRANT_DIMENSION.SCOPE_MODE)
                .values(dimensionId, grantId, "MARKET", "SPECIFIED")
                .execute();
            dsl.insertInto(IAM_GRANT_TARGET,
                    IAM_GRANT_TARGET.ID, IAM_GRANT_TARGET.DIMENSION_ID,
                    IAM_GRANT_TARGET.TARGET_REF)
                .values(8_913_000L + index, dimensionId, "expired-target-" + index)
                .execute();
        }
    }

    private static void assertAuthorizationSubjectInvalid(
        JooqMembershipVersionRepository permissionVersions,
        JooqPermissionGrantRepository grants) {
        assertThrows(InvalidAuthorizationSubjectException.class,
            () -> permissionVersions.findPermissionVersion(TENANT_ID, MEMBERSHIP_ID));
        assertThrows(InvalidAuthorizationSubjectException.class,
            () -> grants.load(TENANT_ID, MEMBERSHIP_ID, 17L));
    }

    private static void assertDisabledSnapshot(com.niv.payment.permission.domain.GrantSnapshot snapshot) {
        assertTrue(snapshot.grants().isEmpty());
        assertNull(snapshot.refreshAfter());
    }
}
