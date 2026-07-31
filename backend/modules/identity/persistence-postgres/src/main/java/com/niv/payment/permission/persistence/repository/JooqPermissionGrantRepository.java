package com.niv.payment.permission.persistence.repository;

import com.niv.payment.permission.domain.GrantSnapshot;
import com.niv.payment.permission.port.InvalidAuthorizationSubjectException;
import com.niv.payment.permission.port.PermissionGrantRepository;
import com.niv.payment.permission.port.StalePermissionVersionException;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;

import java.time.OffsetDateTime;
import java.util.Objects;

import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_AUTHENTICATION_CREDENTIAL;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_GRANT_DIMENSION;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_GRANT_TARGET;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_MEMBERSHIP;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_MEMBERSHIP_ROLE;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_PERMISSION;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_ROLE;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_ROLE_GRANT;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_TENANT;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_USER;

public final class JooqPermissionGrantRepository implements PermissionGrantRepository {
    /**
     * Hard safety ceiling for the joined grant/dimension/target rows in one membership snapshot.
     * Larger assignments must use grouped or relationship-based scopes instead of enumerating
     * unbounded targets in an authorization decision.
     */
    public static final int MAX_SNAPSHOT_DETAIL_ROWS = 4_096;

    private static final String ACTIVE = "ACTIVE";

    private final DSLContext dsl;
    private final GrantSnapshotAssembler assembler;

    public JooqPermissionGrantRepository(DSLContext dsl) {
        this.dsl = Objects.requireNonNull(dsl, "dsl");
        this.assembler = new GrantSnapshotAssembler();
    }

    @Override
    public GrantSnapshot load(long tenantId, long membershipId, long permissionVersion) {
        Field<OffsetDateTime> evaluatedAt = DSL.function(
            DSL.name("statement_timestamp"), SQLDataType.OFFSETDATETIME);
        Field<OffsetDateTime> refreshAfter = refreshAfter(evaluatedAt);
        var effectivePermission = IAM_PERMISSION.as("effective_permission");
        Condition effectiveNow = IAM_ROLE_GRANT.VALID_FROM.isNull()
            .or(IAM_ROLE_GRANT.VALID_FROM.le(evaluatedAt))
            .and(IAM_ROLE_GRANT.VALID_UNTIL.isNull()
                .or(IAM_ROLE_GRANT.VALID_UNTIL.gt(evaluatedAt)));

        var records = dsl.select(
                evaluatedAt,
                refreshAfter,
                IAM_ROLE_GRANT.ID,
                IAM_ROLE_GRANT.ROLE_ID,
                IAM_ROLE_GRANT.VALID_FROM,
                IAM_ROLE_GRANT.VALID_UNTIL,
                IAM_PERMISSION.PERMISSION_CODE,
                IAM_PERMISSION.RISK_LEVEL,
                IAM_PERMISSION.CROSS_TENANT_MODE,
                IAM_PERMISSION.REQUIRED_DIMENSIONS,
                IAM_PERMISSION.REQUIRES_STEP_UP,
                IAM_PERMISSION.REQUIRES_APPROVAL,
                IAM_GRANT_DIMENSION.ID,
                IAM_GRANT_DIMENSION.DIMENSION_CODE,
                IAM_GRANT_DIMENSION.SCOPE_MODE,
                IAM_GRANT_TARGET.TARGET_REF)
            .from(IAM_MEMBERSHIP)
            .join(IAM_TENANT)
                .on(IAM_TENANT.ID.eq(IAM_MEMBERSHIP.TENANT_ID)
                    .and(IAM_TENANT.STATUS.eq(ACTIVE)))
            .join(IAM_USER)
                .on(IAM_USER.ID.eq(IAM_MEMBERSHIP.USER_ID)
                    .and(IAM_USER.STATUS.eq(ACTIVE)))
            .join(IAM_AUTHENTICATION_CREDENTIAL)
                .on(IAM_AUTHENTICATION_CREDENTIAL.USER_ID.eq(IAM_USER.ID)
                    .and(IAM_AUTHENTICATION_CREDENTIAL.STATUS.eq(ACTIVE))
                    .and(IAM_AUTHENTICATION_CREDENTIAL.PASSWORD_HASH.isNotNull()))
            .leftJoin(IAM_MEMBERSHIP_ROLE)
                .on(IAM_MEMBERSHIP_ROLE.TENANT_ID.eq(IAM_MEMBERSHIP.TENANT_ID)
                    .and(IAM_MEMBERSHIP_ROLE.MEMBERSHIP_ID.eq(IAM_MEMBERSHIP.ID)))
            .leftJoin(IAM_ROLE)
                .on(IAM_ROLE.ID.eq(IAM_MEMBERSHIP_ROLE.ROLE_ID)
                    .and(IAM_ROLE.TENANT_ID.eq(IAM_MEMBERSHIP.TENANT_ID))
                    .and(IAM_ROLE.STATUS.eq(ACTIVE))
                    .and(IAM_ROLE.DELETED_AT.isNull()))
            .leftJoin(IAM_ROLE_GRANT)
                .on(IAM_ROLE_GRANT.ROLE_ID.eq(IAM_ROLE.ID)
                    .and(IAM_ROLE_GRANT.TENANT_ID.eq(IAM_MEMBERSHIP.TENANT_ID))
                    .and(IAM_ROLE_GRANT.STATUS.eq(ACTIVE))
                    .and(effectiveNow)
                    .andExists(DSL.selectOne()
                        .from(effectivePermission)
                        .where(effectivePermission.ID.eq(IAM_ROLE_GRANT.PERMISSION_ID)
                            .and(effectivePermission.STATUS.eq(ACTIVE)))))
            .leftJoin(IAM_PERMISSION)
                .on(IAM_PERMISSION.ID.eq(IAM_ROLE_GRANT.PERMISSION_ID)
                    .and(IAM_PERMISSION.STATUS.eq(ACTIVE)))
            .leftJoin(IAM_GRANT_DIMENSION)
                .on(IAM_GRANT_DIMENSION.GRANT_ID.eq(IAM_ROLE_GRANT.ID))
            .leftJoin(IAM_GRANT_TARGET)
                .on(IAM_GRANT_TARGET.DIMENSION_ID.eq(IAM_GRANT_DIMENSION.ID))
            .where(IAM_MEMBERSHIP.TENANT_ID.eq(tenantId)
                .and(IAM_MEMBERSHIP.ID.eq(membershipId))
                .and(IAM_MEMBERSHIP.PERMISSION_VERSION.eq(permissionVersion))
                .and(IAM_MEMBERSHIP.STATUS.eq(ACTIVE)))
            .orderBy(
                IAM_ROLE_GRANT.ID,
                IAM_GRANT_DIMENSION.ID.nullsLast(),
                IAM_GRANT_TARGET.TARGET_REF.nullsLast())
            .limit(MAX_SNAPSHOT_DETAIL_ROWS + 1)
            .fetch();

        if (records.isEmpty()) {
            Long currentVersion = dsl.select(IAM_MEMBERSHIP.PERMISSION_VERSION)
                .from(IAM_MEMBERSHIP)
                .join(IAM_TENANT)
                    .on(IAM_TENANT.ID.eq(IAM_MEMBERSHIP.TENANT_ID)
                        .and(IAM_TENANT.STATUS.eq(ACTIVE)))
                .join(IAM_USER)
                    .on(IAM_USER.ID.eq(IAM_MEMBERSHIP.USER_ID)
                        .and(IAM_USER.STATUS.eq(ACTIVE)))
                .join(IAM_AUTHENTICATION_CREDENTIAL)
                    .on(IAM_AUTHENTICATION_CREDENTIAL.USER_ID.eq(IAM_USER.ID)
                        .and(IAM_AUTHENTICATION_CREDENTIAL.STATUS.eq(ACTIVE))
                        .and(IAM_AUTHENTICATION_CREDENTIAL.PASSWORD_HASH.isNotNull()))
                .where(IAM_MEMBERSHIP.TENANT_ID.eq(tenantId)
                    .and(IAM_MEMBERSHIP.ID.eq(membershipId))
                    .and(IAM_MEMBERSHIP.STATUS.eq(ACTIVE)))
                .fetchOne(IAM_MEMBERSHIP.PERMISSION_VERSION);
            if (currentVersion != null && currentVersion != permissionVersion) {
                throw new StalePermissionVersionException();
            }
            throw new InvalidAuthorizationSubjectException();
        }
        if (records.size() > MAX_SNAPSHOT_DETAIL_ROWS) {
            throw new IllegalStateException(
                "Permission snapshot exceeds the safe detail row limit of "
                    + MAX_SNAPSHOT_DETAIL_ROWS);
        }
        OffsetDateTime databaseEvaluationTime = records.getFirst().get(evaluatedAt);
        OffsetDateTime nextBoundary = records.getFirst().get(refreshAfter);
        var rows = records.stream()
            .filter(record -> record.get(IAM_ROLE_GRANT.ID) != null)
            .map(record -> new GrantSnapshotAssembler.GrantRow(
                record.get(IAM_ROLE_GRANT.ID),
                record.get(IAM_ROLE_GRANT.ROLE_ID),
                record.get(IAM_ROLE_GRANT.VALID_FROM),
                record.get(IAM_ROLE_GRANT.VALID_UNTIL),
                record.get(IAM_PERMISSION.PERMISSION_CODE),
                record.get(IAM_PERMISSION.RISK_LEVEL),
                record.get(IAM_PERMISSION.CROSS_TENANT_MODE),
                record.get(IAM_PERMISSION.REQUIRED_DIMENSIONS),
                record.get(IAM_PERMISSION.REQUIRES_STEP_UP),
                record.get(IAM_PERMISSION.REQUIRES_APPROVAL),
                record.get(IAM_GRANT_DIMENSION.ID),
                record.get(IAM_GRANT_DIMENSION.DIMENSION_CODE),
                record.get(IAM_GRANT_DIMENSION.SCOPE_MODE),
                record.get(IAM_GRANT_TARGET.TARGET_REF)))
            .toList();

        return assembler.assemble(
            tenantId,
            membershipId,
            permissionVersion,
            databaseEvaluationTime,
            nextBoundary,
            rows);
    }

    /**
     * Finds the nearest grant activation or expiration without joining grant dimension/target
     * detail. The correlated scalar subquery is evaluated in the same SQL statement and MVCC view
     * as the membership-version guard and the effective-grant detail rows.
     */
    private static Field<OffsetDateTime> refreshAfter(Field<OffsetDateTime> evaluatedAt) {
        var membershipRole = IAM_MEMBERSHIP_ROLE.as("boundary_membership_role");
        var role = IAM_ROLE.as("boundary_role");
        var grant = IAM_ROLE_GRANT.as("boundary_grant");
        var permission = IAM_PERMISSION.as("boundary_permission");

        Field<OffsetDateTime> nextValidFrom = DSL.min(
            DSL.when(grant.VALID_FROM.gt(evaluatedAt), grant.VALID_FROM));
        Condition currentlyEffective = grant.VALID_UNTIL.gt(evaluatedAt)
            .and(grant.VALID_FROM.isNull().or(grant.VALID_FROM.le(evaluatedAt)));
        Field<OffsetDateTime> nextValidUntil = DSL.min(
            DSL.when(currentlyEffective, grant.VALID_UNTIL));

        return DSL.select(DSL.least(nextValidFrom, nextValidUntil))
            .from(membershipRole)
            .join(role)
                .on(role.ID.eq(membershipRole.ROLE_ID)
                    .and(role.TENANT_ID.eq(membershipRole.TENANT_ID))
                    .and(role.STATUS.eq(ACTIVE))
                    .and(role.DELETED_AT.isNull()))
            .join(grant)
                .on(grant.ROLE_ID.eq(role.ID)
                    .and(grant.TENANT_ID.eq(role.TENANT_ID))
                    .and(grant.STATUS.eq(ACTIVE)))
            .join(permission)
                .on(permission.ID.eq(grant.PERMISSION_ID)
                    .and(permission.STATUS.eq(ACTIVE)))
            .where(membershipRole.TENANT_ID.eq(IAM_MEMBERSHIP.TENANT_ID)
                .and(membershipRole.MEMBERSHIP_ID.eq(IAM_MEMBERSHIP.ID)))
            .asField("refresh_after");
    }
}
