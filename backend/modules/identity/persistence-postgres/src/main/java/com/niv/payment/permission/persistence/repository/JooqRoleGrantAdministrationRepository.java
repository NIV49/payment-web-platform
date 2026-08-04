package com.niv.payment.permission.persistence.repository;

import com.niv.payment.permission.domain.AdministrationActor;
import com.niv.payment.permission.domain.AccountDomain;
import com.niv.payment.permission.domain.PermissionCode;
import com.niv.payment.permission.domain.ScopeDimension;
import com.niv.payment.permission.domain.ScopeMode;
import com.niv.payment.permission.service.IdentityAdministrationService;
import com.niv.payment.permission.service.RoleAssignmentPolicy;
import com.niv.payment.permission.service.RoleGrantAdministrationService;
import com.niv.payment.permission.service.RoleGrantChangeCommand;
import com.niv.payment.permission.service.RoleGrantModels;
import com.niv.payment.permission.service.RoleGrantReadPort;
import com.niv.payment.permission.service.RoleGrantWritePort;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_AUDIT_EVENT;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_GRANT_DIMENSION;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_GRANT_TARGET;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_MEMBERSHIP;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_MEMBERSHIP_ROLE;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_PERMISSION;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_PERMISSION_CHANGE_OUTBOX;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_ROLE;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_ROLE_GRANT;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_TENANT;
import static com.niv.payment.permission.persistence.repository.JooqAdministrationSupport.ACTIVE;
import static com.niv.payment.permission.persistence.repository.JooqAdministrationSupport.DISABLED;
import static com.niv.payment.permission.persistence.repository.JooqAdministrationSupport.notFound;

/** Atomic jOOQ boundary for the deliberately constrained tenant-wide role grant editor. */
public class JooqRoleGrantAdministrationRepository implements RoleGrantReadPort, RoleGrantWritePort {
    private static final Set<String> LEGACY_COMPATIBILITY_CODES =
        Set.of("menu:manage", "department:manage");
    private static final Set<String> PROTECTED_PORTAL_PERMISSION_CODES = Set.of(
        AccountDomain.PLATFORM.accessPermissionCode(),
        AccountDomain.MERCHANT.accessPermissionCode(),
        AccountDomain.AGENT.accessPermissionCode());
    private static final Set<String> REPLACEABLE_PERMISSION_CODES =
        java.util.stream.Stream.concat(
                RoleGrantAdministrationService.GRANTABLE_CODES.stream(),
                LEGACY_COMPATIBILITY_CODES.stream())
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    private static final Set<String> REQUIRED_TRANSACTIONAL_PERMISSIONS = Set.of(
        "role:view", RoleGrantAdministrationService.GRANT_UPDATE_PERMISSION);

    private final DSLContext dsl;
    private final JooqAdministrationSupport support;
    private final Supplier<String> traceIdSupplier;

    public JooqRoleGrantAdministrationRepository(DSLContext dsl, Supplier<String> traceIdSupplier) {
        this.dsl = Objects.requireNonNull(dsl, "dsl");
        this.traceIdSupplier = Objects.requireNonNull(traceIdSupplier, "traceIdSupplier");
        this.support = new JooqAdministrationSupport(dsl, traceIdSupplier);
    }

    @Override
    public List<RoleGrantModels.GrantablePermission> findGrantablePermissions(
        long tenantId, AdministrationActor actor) {
        support.validateActor(tenantId, actor);
        requireSystemActor(tenantId, actor.membershipId(), false);
        return dsl.select(IAM_PERMISSION.PERMISSION_CODE, IAM_PERMISSION.RESOURCE_CODE,
                IAM_PERMISSION.ACTION_CODE)
            .from(IAM_PERMISSION)
            .where(IAM_PERMISSION.PERMISSION_CODE.in(RoleGrantAdministrationService.GRANTABLE_CODES)
                .and(IAM_PERMISSION.STATUS.eq(ACTIVE))
                .and(IAM_PERMISSION.RISK_LEVEL.eq("NORMAL"))
                .and(IAM_PERMISSION.CROSS_TENANT_MODE.eq("SAME_TENANT_ONLY"))
                .and(IAM_PERMISSION.REQUIRED_DIMENSIONS.eq(new String[]{"TENANT"}))
                .and(IAM_PERMISSION.REQUIRES_STEP_UP.isFalse())
                .and(IAM_PERMISSION.REQUIRES_APPROVAL.isFalse()))
            .orderBy(IAM_PERMISSION.PERMISSION_CODE)
            .fetch(row -> new RoleGrantModels.GrantablePermission(
                PermissionCode.of(row.get(IAM_PERMISSION.PERMISSION_CODE)),
                row.get(IAM_PERMISSION.RESOURCE_CODE), row.get(IAM_PERMISSION.ACTION_CODE)));
    }

    @Override
    public RoleGrantModels.RoleGrants findRoleGrants(long tenantId, AdministrationActor actor, long roleId) {
        support.validateActor(tenantId, actor);
        requireSystemActor(tenantId, actor.membershipId(), false);
        return loadRoleGrants(tenantId, roleId);
    }

    @Override
    @Transactional
    public RoleGrantModels.RoleGrants replaceAtomically(RoleGrantChangeCommand command) {
        support.lockTenant(command.tenantId(), command.actor());

        var role = dsl.select(IAM_ROLE.ROW_VERSION, IAM_ROLE.SYSTEM_ROLE, IAM_ROLE.ASSIGNABLE)
            .from(IAM_ROLE)
            .where(IAM_ROLE.TENANT_ID.eq(command.tenantId())
                .and(IAM_ROLE.ID.eq(command.roleId()))
                .and(IAM_ROLE.DELETED_AT.isNull()))
            .forUpdate()
            .fetchOne();
        if (role == null) {
            throw notFound("Role");
        }
        requireSystemActor(command.tenantId(), command.actor().membershipId(), true);
        requireTransactionalPermissions(
            command.tenantId(), command.actor().membershipId(), REQUIRED_TRANSACTIONAL_PERMISSIONS);
        if (Boolean.TRUE.equals(role.get(IAM_ROLE.SYSTEM_ROLE))) {
            throw new SecurityException("System roles cannot be edited");
        }
        if (!Boolean.TRUE.equals(role.get(IAM_ROLE.ASSIGNABLE))) {
            throw new RoleAssignmentPolicy.RoleNotAssignableException();
        }
        long currentVersion = role.get(IAM_ROLE.ROW_VERSION);
        if (currentVersion != command.expectedRoleVersion()) {
            throw new IdentityAdministrationService.OptimisticLockException();
        }

        RoleGrantModels.RoleGrants existing = loadRoleGrants(command.tenantId(), command.roleId());
        if (!existing.editable()) {
            throw new IdentityAdministrationService.DataConflictException(
                "Role contains grants outside the supported administration surface");
        }
        Map<String, Long> permissionIds = requireCatalog(command.grants());
        Field<JSONB> before = auditValue(currentVersion, "existing", existing.grants(), command.reason());

        replaceGrantRows(
            command.tenantId(), command.roleId(), command.actor().membershipId(),
            command.grants(), permissionIds);

        long nextVersion = currentVersion + 1L;
        int roleUpdated = dsl.update(IAM_ROLE)
            .set(IAM_ROLE.ROW_VERSION, nextVersion)
            .set(IAM_ROLE.UPDATED_AT, DSL.currentOffsetDateTime())
            .where(IAM_ROLE.TENANT_ID.eq(command.tenantId())
                .and(IAM_ROLE.ID.eq(command.roleId()))
                .and(IAM_ROLE.ROW_VERSION.eq(currentVersion))
                .and(IAM_ROLE.SYSTEM_ROLE.isFalse())
                .and(IAM_ROLE.ASSIGNABLE.isTrue())
                .and(IAM_ROLE.DELETED_AT.isNull()))
            .execute();
        if (roleUpdated != 1) {
            throw new IdentityAdministrationService.OptimisticLockException();
        }
        dsl.update(IAM_MEMBERSHIP)
            .set(IAM_MEMBERSHIP.PERMISSION_VERSION, IAM_MEMBERSHIP.PERMISSION_VERSION.plus(1L))
            .set(IAM_MEMBERSHIP.UPDATED_AT, DSL.currentOffsetDateTime())
            .where(IAM_MEMBERSHIP.TENANT_ID.eq(command.tenantId())
                .andExists(dsl.selectOne().from(IAM_MEMBERSHIP_ROLE)
                    .where(IAM_MEMBERSHIP_ROLE.TENANT_ID.eq(IAM_MEMBERSHIP.TENANT_ID)
                        .and(IAM_MEMBERSHIP_ROLE.MEMBERSHIP_ID.eq(IAM_MEMBERSHIP.ID))
                        .and(IAM_MEMBERSHIP_ROLE.ROLE_ID.eq(command.roleId())))))
            .execute();

        Field<JSONB> after = auditValue(nextVersion, "replacement", command.grants(), command.reason());
        String traceId = traceIdSupplier.get();
        dsl.insertInto(IAM_AUDIT_EVENT)
            .set(IAM_AUDIT_EVENT.ID, support.nextId())
            .set(IAM_AUDIT_EVENT.TENANT_ID, command.tenantId())
            .set(IAM_AUDIT_EVENT.OPERATOR_MEMBERSHIP_ID, command.actor().membershipId())
            .set(IAM_AUDIT_EVENT.TARGET_TYPE, "ROLE_GRANTS")
            .set(IAM_AUDIT_EVENT.TARGET_REF, Long.toString(command.roleId()))
            .set(IAM_AUDIT_EVENT.ACTION_CODE, "REPLACE")
            .set(IAM_AUDIT_EVENT.DECISION, "ALLOW")
            .set(IAM_AUDIT_EVENT.REASON_CODE, "AUTHORIZED")
            .set(IAM_AUDIT_EVENT.PERMISSION_CODE, RoleGrantAdministrationService.GRANT_UPDATE_PERMISSION)
            .set(IAM_AUDIT_EVENT.BEFORE_VALUE, before)
            .set(IAM_AUDIT_EVENT.AFTER_VALUE, after)
            .set(IAM_AUDIT_EVENT.TRACE_ID, traceId)
            .execute();
        dsl.insertInto(IAM_PERMISSION_CHANGE_OUTBOX)
            .set(IAM_PERMISSION_CHANGE_OUTBOX.ID, support.nextId())
            .set(IAM_PERMISSION_CHANGE_OUTBOX.TENANT_ID, command.tenantId())
            .set(IAM_PERMISSION_CHANGE_OUTBOX.AGGREGATE_TYPE, "ROLE_GRANTS")
            .set(IAM_PERMISSION_CHANGE_OUTBOX.AGGREGATE_REF, Long.toString(command.roleId()))
            .set(IAM_PERMISSION_CHANGE_OUTBOX.EVENT_TYPE, "ROLE_GRANTS_REPLACED")
            .set(IAM_PERMISSION_CHANGE_OUTBOX.PAYLOAD, after)
            .set(IAM_PERMISSION_CHANGE_OUTBOX.AGGREGATE_VERSION, nextVersion)
            .set(IAM_PERMISSION_CHANGE_OUTBOX.SCHEMA_VERSION, 1)
            .set(IAM_PERMISSION_CHANGE_OUTBOX.PARTITION_KEY,
                command.tenantId() + ":" + command.roleId())
            .set(IAM_PERMISSION_CHANGE_OUTBOX.TRACE_ID, traceId)
            .execute();

        return new RoleGrantModels.RoleGrants(command.roleId(), nextVersion, true,
            command.grants());
    }

    RoleGrantModels.RoleGrants loadRoleGrants(long tenantId, long roleId) {
        var role = dsl.select(IAM_ROLE.ROW_VERSION, IAM_ROLE.SYSTEM_ROLE, IAM_ROLE.ASSIGNABLE,
                IAM_TENANT.ACCOUNT_DOMAIN)
            .from(IAM_ROLE)
            .join(IAM_TENANT).on(IAM_TENANT.ID.eq(IAM_ROLE.TENANT_ID))
            .where(IAM_ROLE.TENANT_ID.eq(tenantId)
                .and(IAM_ROLE.ID.eq(roleId))
                .and(IAM_ROLE.DELETED_AT.isNull()))
            .fetchOne();
        if (role == null) {
            throw notFound("Role");
        }
        var rows = dsl.select(IAM_ROLE_GRANT.ID, IAM_ROLE_GRANT.GRANT_KEY,
                IAM_ROLE_GRANT.STATUS,
                IAM_ROLE_GRANT.VALID_FROM, IAM_ROLE_GRANT.VALID_UNTIL,
                IAM_PERMISSION.PERMISSION_CODE, IAM_PERMISSION.RISK_LEVEL,
                IAM_PERMISSION.CROSS_TENANT_MODE, IAM_PERMISSION.REQUIRED_DIMENSIONS,
                IAM_PERMISSION.REQUIRES_STEP_UP, IAM_PERMISSION.REQUIRES_APPROVAL,
                IAM_PERMISSION.STATUS, IAM_GRANT_DIMENSION.DIMENSION_CODE,
                IAM_GRANT_DIMENSION.SCOPE_MODE)
            .from(IAM_ROLE_GRANT)
            .join(IAM_PERMISSION).on(IAM_PERMISSION.ID.eq(IAM_ROLE_GRANT.PERMISSION_ID))
            .leftJoin(IAM_GRANT_DIMENSION).on(IAM_GRANT_DIMENSION.GRANT_ID.eq(IAM_ROLE_GRANT.ID))
            .where(IAM_ROLE_GRANT.TENANT_ID.eq(tenantId)
                .and(IAM_ROLE_GRANT.ROLE_ID.eq(roleId))
                .and(IAM_ROLE_GRANT.STATUS.eq(ACTIVE)
                    .and(IAM_PERMISSION.PERMISSION_CODE.notIn(LEGACY_COMPATIBILITY_CODES))
                    .or(IAM_PERMISSION.PERMISSION_CODE.in(PROTECTED_PORTAL_PERMISSION_CODES))
                    .or(IAM_ROLE_GRANT.GRANT_KEY.eq(
                        RoleGrantAdministrationService.PROTECTED_PORTAL_GRANT_KEY))))
            .orderBy(IAM_ROLE_GRANT.GRANT_KEY, IAM_GRANT_DIMENSION.DIMENSION_CODE)
            .fetch();
        Map<Long, List<Record>> byGrant = new LinkedHashMap<>();
        rows.forEach(row -> byGrant.computeIfAbsent(row.get(IAM_ROLE_GRANT.ID), ignored -> new ArrayList<>()).add(row));
        Set<Long> targetedGrantIds = byGrant.isEmpty()
            ? Set.of()
            : dsl.selectDistinct(IAM_GRANT_DIMENSION.GRANT_ID)
                .from(IAM_GRANT_TARGET)
                .join(IAM_GRANT_DIMENSION).on(IAM_GRANT_DIMENSION.ID.eq(IAM_GRANT_TARGET.DIMENSION_ID))
                .join(IAM_ROLE_GRANT).on(IAM_ROLE_GRANT.ID.eq(IAM_GRANT_DIMENSION.GRANT_ID))
                .where(IAM_ROLE_GRANT.TENANT_ID.eq(tenantId)
                    .and(IAM_ROLE_GRANT.ROLE_ID.eq(roleId))
                    .and(IAM_ROLE_GRANT.STATUS.eq(ACTIVE)))
                .fetchSet(IAM_GRANT_DIMENSION.GRANT_ID);
        List<RoleGrantModels.Selection> selections = new ArrayList<>();
        boolean editable = !Boolean.TRUE.equals(role.get(IAM_ROLE.SYSTEM_ROLE))
            && Boolean.TRUE.equals(role.get(IAM_ROLE.ASSIGNABLE));
        String expectedPortalCode = expectedPortalPermissionCode(role.get(IAM_TENANT.ACCOUNT_DOMAIN));
        int protectedPortalGrantCount = 0;
        boolean protectedPortalGrantValid = false;
        Set<String> observedGrantKeys = new HashSet<>();
        Set<String> observedPermissionCodes = new HashSet<>();
        for (List<Record> grantRows : byGrant.values()) {
            Record first = grantRows.getFirst();
            String code = first.get(IAM_PERMISSION.PERMISSION_CODE);
            editable &= observedGrantKeys.add(first.get(IAM_ROLE_GRANT.GRANT_KEY));
            editable &= observedPermissionCodes.add(code);
            boolean hasTargets = targetedGrantIds.contains(first.get(IAM_ROLE_GRANT.ID));
            boolean safeTenantGrant = ACTIVE.equals(first.get(IAM_ROLE_GRANT.STATUS))
                && ACTIVE.equals(first.get(IAM_PERMISSION.STATUS))
                && "NORMAL".equals(first.get(IAM_PERMISSION.RISK_LEVEL))
                && "SAME_TENANT_ONLY".equals(first.get(IAM_PERMISSION.CROSS_TENANT_MODE))
                && java.util.Arrays.equals(new String[]{"TENANT"}, first.get(IAM_PERMISSION.REQUIRED_DIMENSIONS))
                && !Boolean.TRUE.equals(first.get(IAM_PERMISSION.REQUIRES_STEP_UP))
                && !Boolean.TRUE.equals(first.get(IAM_PERMISSION.REQUIRES_APPROVAL))
                && first.get(IAM_ROLE_GRANT.VALID_FROM) == null
                && first.get(IAM_ROLE_GRANT.VALID_UNTIL) == null
                && grantRows.size() == 1
                && "TENANT".equals(first.get(IAM_GRANT_DIMENSION.DIMENSION_CODE))
                && "TENANT_ALL".equals(first.get(IAM_GRANT_DIMENSION.SCOPE_MODE))
                && !hasTargets;
            if (PROTECTED_PORTAL_PERMISSION_CODES.contains(code)) {
                protectedPortalGrantCount++;
                protectedPortalGrantValid |= expectedPortalCode.equals(code)
                    && RoleGrantAdministrationService.PROTECTED_PORTAL_GRANT_KEY.equals(
                        first.get(IAM_ROLE_GRANT.GRANT_KEY))
                    && safeTenantGrant;
                continue;
            }
            if (RoleGrantAdministrationService.PROTECTED_PORTAL_GRANT_KEY.equals(
                    first.get(IAM_ROLE_GRANT.GRANT_KEY))) {
                editable = false;
                continue;
            }
            boolean supported = RoleGrantAdministrationService.GRANTABLE_CODES.contains(code)
                && safeTenantGrant;
            editable &= supported;
            if (first.get(IAM_GRANT_DIMENSION.DIMENSION_CODE) != null) {
                try {
                    selections.add(new RoleGrantModels.Selection(
                        first.get(IAM_ROLE_GRANT.GRANT_KEY), PermissionCode.of(code),
                        ScopeDimension.valueOf(first.get(IAM_GRANT_DIMENSION.DIMENSION_CODE)),
                        ScopeMode.valueOf(first.get(IAM_GRANT_DIMENSION.SCOPE_MODE))));
                } catch (IllegalArgumentException invalidStoredGrant) {
                    editable = false;
                }
            }
        }
        editable &= protectedPortalGrantCount == 1 && protectedPortalGrantValid;
        selections.sort(Comparator.comparing(item -> item.permission().value()));
        return new RoleGrantModels.RoleGrants(roleId, role.get(IAM_ROLE.ROW_VERSION), editable, selections);
    }

    Map<String, Long> requireCatalog(List<RoleGrantModels.Selection> requested) {
        if (requested.isEmpty()) {
            return Map.of();
        }
        Set<String> codes = requested.stream().map(item -> item.permission().value())
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Map<String, Long> result = dsl.select(IAM_PERMISSION.PERMISSION_CODE, IAM_PERMISSION.ID)
            .from(IAM_PERMISSION)
            .where(IAM_PERMISSION.PERMISSION_CODE.in(codes)
                .and(IAM_PERMISSION.STATUS.eq(ACTIVE))
                .and(IAM_PERMISSION.RISK_LEVEL.eq("NORMAL"))
                .and(IAM_PERMISSION.CROSS_TENANT_MODE.eq("SAME_TENANT_ONLY"))
                .and(IAM_PERMISSION.REQUIRED_DIMENSIONS.eq(new String[]{"TENANT"}))
                .and(IAM_PERMISSION.REQUIRES_STEP_UP.isFalse())
                .and(IAM_PERMISSION.REQUIRES_APPROVAL.isFalse()))
            .fetchMap(IAM_PERMISSION.PERMISSION_CODE, IAM_PERMISSION.ID);
        if (result.size() != codes.size()) {
            throw new IllegalArgumentException("Unknown, disabled, or unsafe permission requested");
        }
        return result;
    }

    void requireSystemActor(long tenantId, long membershipId, boolean lock) {
        var query = dsl.select(IAM_ROLE.ID)
            .from(IAM_MEMBERSHIP_ROLE)
            .join(IAM_ROLE).on(IAM_ROLE.TENANT_ID.eq(IAM_MEMBERSHIP_ROLE.TENANT_ID)
                .and(IAM_ROLE.ID.eq(IAM_MEMBERSHIP_ROLE.ROLE_ID)))
            .where(IAM_MEMBERSHIP_ROLE.TENANT_ID.eq(tenantId)
                .and(IAM_MEMBERSHIP_ROLE.MEMBERSHIP_ID.eq(membershipId))
                .and(IAM_ROLE.SYSTEM_ROLE.isTrue())
                .and(IAM_ROLE.STATUS.eq(ACTIVE))
                .and(IAM_ROLE.DELETED_AT.isNull()));
        boolean systemActor = lock
            ? query.forUpdate().of(IAM_ROLE).fetchAny() != null
            : query.fetchAny() != null;
        if (!systemActor) {
            throw new SecurityException("An active system role is required");
        }
    }

    void requireTransactionalPermissions(
        long tenantId, long membershipId, Set<String> requiredPermissions) {
        var membershipRole = IAM_MEMBERSHIP_ROLE.as("authorization_membership_role");
        var role = IAM_ROLE.as("authorization_role");
        var grant = IAM_ROLE_GRANT.as("authorization_grant");
        var permission = IAM_PERMISSION.as("authorization_permission");
        var dimension = IAM_GRANT_DIMENSION.as("authorization_dimension");
        var extraDimension = IAM_GRANT_DIMENSION.as("authorization_extra_dimension");
        var target = IAM_GRANT_TARGET.as("authorization_target");
        Field<OffsetDateTime> evaluatedAt = DSL.function(
            DSL.name("statement_timestamp"), SQLDataType.OFFSETDATETIME);

        Set<String> effectivePermissions = dsl.selectDistinct(permission.PERMISSION_CODE)
            .from(membershipRole)
            .join(role).on(role.TENANT_ID.eq(membershipRole.TENANT_ID)
                .and(role.ID.eq(membershipRole.ROLE_ID))
                .and(role.STATUS.eq(ACTIVE))
                .and(role.DELETED_AT.isNull()))
            .join(grant).on(grant.TENANT_ID.eq(role.TENANT_ID)
                .and(grant.ROLE_ID.eq(role.ID))
                .and(grant.STATUS.eq(ACTIVE))
                .and(grant.VALID_FROM.isNull().or(grant.VALID_FROM.le(evaluatedAt)))
                .and(grant.VALID_UNTIL.isNull().or(grant.VALID_UNTIL.gt(evaluatedAt))))
            .join(permission).on(permission.ID.eq(grant.PERMISSION_ID)
                .and(permission.STATUS.eq(ACTIVE))
                .and(permission.PERMISSION_CODE.in(requiredPermissions))
                .and(permission.RISK_LEVEL.eq("NORMAL"))
                .and(permission.CROSS_TENANT_MODE.eq("SAME_TENANT_ONLY"))
                .and(permission.REQUIRED_DIMENSIONS.eq(new String[]{"TENANT"}))
                .and(permission.REQUIRES_STEP_UP.isFalse())
                .and(permission.REQUIRES_APPROVAL.isFalse()))
            .join(dimension).on(dimension.GRANT_ID.eq(grant.ID)
                .and(dimension.DIMENSION_CODE.eq(ScopeDimension.TENANT.name()))
                .and(dimension.SCOPE_MODE.eq(ScopeMode.TENANT_ALL.name())))
            .where(membershipRole.TENANT_ID.eq(tenantId)
                .and(membershipRole.MEMBERSHIP_ID.eq(membershipId))
                .andNotExists(DSL.selectOne()
                    .from(extraDimension)
                    .where(extraDimension.GRANT_ID.eq(grant.ID)
                        .and(extraDimension.ID.ne(dimension.ID))))
                .andNotExists(DSL.selectOne()
                    .from(target)
                    .where(target.DIMENSION_ID.eq(dimension.ID))))
            .fetchSet(permission.PERMISSION_CODE);
        if (!effectivePermissions.equals(requiredPermissions)) {
            throw new SecurityException(
                "Current role grant administration permissions are required");
        }
    }

    void replaceGrantRows(long tenantId, long roleId, long actorMembershipId,
                          List<RoleGrantModels.Selection> requested,
                          Map<String, Long> permissionIds) {
        dsl.update(IAM_ROLE_GRANT)
            .set(IAM_ROLE_GRANT.STATUS, DISABLED)
            .set(IAM_ROLE_GRANT.UPDATED_BY, actorMembershipId)
            .set(IAM_ROLE_GRANT.UPDATED_AT, DSL.currentOffsetDateTime())
            .set(IAM_ROLE_GRANT.ROW_VERSION, IAM_ROLE_GRANT.ROW_VERSION.plus(1L))
            .where(IAM_ROLE_GRANT.TENANT_ID.eq(tenantId)
                .and(IAM_ROLE_GRANT.ROLE_ID.eq(roleId))
                .and(IAM_ROLE_GRANT.STATUS.eq(ACTIVE))
                .and(IAM_ROLE_GRANT.PERMISSION_ID.in(
                    dsl.select(IAM_PERMISSION.ID)
                        .from(IAM_PERMISSION)
                        .where(IAM_PERMISSION.PERMISSION_CODE.in(REPLACEABLE_PERMISSION_CODES)))))
            .execute();

        for (RoleGrantModels.Selection selection : requested) {
            long permissionId = permissionIds.get(selection.permission().value());
            Long grantId = dsl.select(IAM_ROLE_GRANT.ID)
                .from(IAM_ROLE_GRANT)
                .where(IAM_ROLE_GRANT.TENANT_ID.eq(tenantId)
                    .and(IAM_ROLE_GRANT.ROLE_ID.eq(roleId))
                    .and(IAM_ROLE_GRANT.PERMISSION_ID.eq(permissionId))
                    .and(IAM_ROLE_GRANT.GRANT_KEY.eq(selection.grantKey())))
                .fetchOne(IAM_ROLE_GRANT.ID);
            if (grantId == null) {
                grantId = support.nextId();
                dsl.insertInto(IAM_ROLE_GRANT)
                    .set(IAM_ROLE_GRANT.ID, grantId)
                    .set(IAM_ROLE_GRANT.TENANT_ID, tenantId)
                    .set(IAM_ROLE_GRANT.ROLE_ID, roleId)
                    .set(IAM_ROLE_GRANT.PERMISSION_ID, permissionId)
                    .set(IAM_ROLE_GRANT.GRANT_KEY, selection.grantKey())
                    .set(IAM_ROLE_GRANT.STATUS, ACTIVE)
                    .set(IAM_ROLE_GRANT.CREATED_BY, actorMembershipId)
                    .set(IAM_ROLE_GRANT.UPDATED_BY, actorMembershipId)
                    .execute();
            } else {
                dsl.update(IAM_ROLE_GRANT)
                    .set(IAM_ROLE_GRANT.STATUS, ACTIVE)
                    .set(IAM_ROLE_GRANT.VALID_FROM, (OffsetDateTime) null)
                    .set(IAM_ROLE_GRANT.VALID_UNTIL, (OffsetDateTime) null)
                    .set(IAM_ROLE_GRANT.UPDATED_BY, actorMembershipId)
                    .set(IAM_ROLE_GRANT.UPDATED_AT, DSL.currentOffsetDateTime())
                    .set(IAM_ROLE_GRANT.ROW_VERSION, IAM_ROLE_GRANT.ROW_VERSION.plus(1L))
                    .where(IAM_ROLE_GRANT.ID.eq(grantId))
                    .execute();
                dsl.deleteFrom(IAM_GRANT_DIMENSION)
                    .where(IAM_GRANT_DIMENSION.GRANT_ID.eq(grantId))
                    .execute();
            }
            dsl.insertInto(IAM_GRANT_DIMENSION)
                .set(IAM_GRANT_DIMENSION.ID, support.nextId())
                .set(IAM_GRANT_DIMENSION.GRANT_ID, grantId)
                .set(IAM_GRANT_DIMENSION.DIMENSION_CODE, ScopeDimension.TENANT.name())
                .set(IAM_GRANT_DIMENSION.SCOPE_MODE, ScopeMode.TENANT_ALL.name())
                .execute();
        }
    }

    void insertProtectedPortalGrant(long tenantId, long roleId, long actorMembershipId) {
        String accountDomain = dsl.select(IAM_TENANT.ACCOUNT_DOMAIN)
            .from(IAM_TENANT)
            .where(IAM_TENANT.ID.eq(tenantId))
            .fetchOne(IAM_TENANT.ACCOUNT_DOMAIN);
        String permissionCode = expectedPortalPermissionCode(accountDomain);
        Long permissionId = dsl.select(IAM_PERMISSION.ID)
            .from(IAM_PERMISSION)
            .where(IAM_PERMISSION.PERMISSION_CODE.eq(permissionCode)
                .and(IAM_PERMISSION.STATUS.eq(ACTIVE))
                .and(IAM_PERMISSION.RISK_LEVEL.eq("NORMAL"))
                .and(IAM_PERMISSION.CROSS_TENANT_MODE.eq("SAME_TENANT_ONLY"))
                .and(IAM_PERMISSION.REQUIRED_DIMENSIONS.eq(new String[]{"TENANT"}))
                .and(IAM_PERMISSION.REQUIRES_STEP_UP.isFalse())
                .and(IAM_PERMISSION.REQUIRES_APPROVAL.isFalse()))
            .fetchOne(IAM_PERMISSION.ID);
        if (permissionId == null) {
            throw new IdentityAdministrationService.DataConflictException(
                "Protected backoffice access permission is unavailable");
        }
        long grantId = support.nextId();
        dsl.insertInto(IAM_ROLE_GRANT)
            .set(IAM_ROLE_GRANT.ID, grantId)
            .set(IAM_ROLE_GRANT.TENANT_ID, tenantId)
            .set(IAM_ROLE_GRANT.ROLE_ID, roleId)
            .set(IAM_ROLE_GRANT.PERMISSION_ID, permissionId)
            .set(IAM_ROLE_GRANT.GRANT_KEY, RoleGrantAdministrationService.PROTECTED_PORTAL_GRANT_KEY)
            .set(IAM_ROLE_GRANT.STATUS, ACTIVE)
            .set(IAM_ROLE_GRANT.CREATED_BY, actorMembershipId)
            .set(IAM_ROLE_GRANT.UPDATED_BY, actorMembershipId)
            .execute();
        dsl.insertInto(IAM_GRANT_DIMENSION)
            .set(IAM_GRANT_DIMENSION.ID, support.nextId())
            .set(IAM_GRANT_DIMENSION.GRANT_ID, grantId)
            .set(IAM_GRANT_DIMENSION.DIMENSION_CODE, ScopeDimension.TENANT.name())
            .set(IAM_GRANT_DIMENSION.SCOPE_MODE, ScopeMode.TENANT_ALL.name())
            .execute();
    }

    private static String expectedPortalPermissionCode(String accountDomain) {
        if (accountDomain == null) {
            throw new IdentityAdministrationService.DataConflictException(
                "Tenant account domain is unavailable");
        }
        try {
            return AccountDomain.valueOf(accountDomain).accessPermissionCode();
        } catch (IllegalArgumentException invalidDomain) {
            throw new IdentityAdministrationService.DataConflictException(
                "Tenant account domain is invalid");
        }
    }

    private static Field<JSONB> auditValue(long roleVersion, String state,
                                           List<RoleGrantModels.Selection> grants, String reason) {
        String permissions = grants.stream().map(item -> item.permission().value()).sorted()
            .collect(java.util.stream.Collectors.joining(","));
        return DSL.field(
            "jsonb_build_object('roleVersion', {0}, 'state', {1}, 'permissions', {2}, 'reason', {3})",
            JSONB.class, DSL.val(roleVersion), DSL.val(state), DSL.val(permissions), DSL.val(reason));
    }
}
