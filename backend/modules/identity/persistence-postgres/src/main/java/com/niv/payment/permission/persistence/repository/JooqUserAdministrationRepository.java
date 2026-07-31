package com.niv.payment.permission.persistence.repository;

import com.niv.payment.permission.domain.AdministrationActor;
import com.niv.payment.permission.port.UserAdministrationPort;
import com.niv.payment.permission.service.IdentityAdministrationService;
import com.niv.payment.permission.service.IdentityModels;
import com.niv.payment.permission.service.LoginCredentialPolicy;
import com.niv.payment.permission.service.RoleAssignmentPolicy;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_AUTHENTICATION_CREDENTIAL;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_DEPARTMENT;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_MEMBERSHIP;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_MEMBERSHIP_ROLE;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_ROLE;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_USER;
import static com.niv.payment.permission.persistence.repository.JooqAdministrationSupport.ACTIVE;
import static com.niv.payment.permission.persistence.repository.JooqAdministrationSupport.TERMINATED;
import static com.niv.payment.permission.persistence.repository.JooqAdministrationSupport.notFound;
import static com.niv.payment.permission.persistence.repository.JooqAdministrationSupport.status;

/** Transactional membership administration with tenant serialization and delegation checks. */
public class JooqUserAdministrationRepository implements UserAdministrationPort {
    private static final String PENDING_ACTIVATION = "PENDING_ACTIVATION";
    private static final String DISABLED = "DISABLED";
    private final DSLContext dsl;
    private final JooqIdentityQueryRepository queries;
    private final JooqAdministrationSupport support;
    private final RoleAssignmentPolicy roleAssignmentPolicy = new RoleAssignmentPolicy();

    public JooqUserAdministrationRepository(DSLContext dsl,
                                            JooqIdentityQueryRepository queries,
                                            Supplier<String> traceIdSupplier) {
        this.dsl = Objects.requireNonNull(dsl, "dsl");
        this.queries = Objects.requireNonNull(queries, "queries");
        this.support = new JooqAdministrationSupport(dsl, traceIdSupplier);
    }

    @Override
    public IdentityModels.Page<IdentityModels.User> findUsers(long tenantId, IdentityModels.UserQuery query) {
        return queries.findUsers(tenantId, query);
    }

    @Override
    @Transactional
    public long createUser(long tenantId, AdministrationActor actor, IdentityModels.UserCreateCommand command) {
        support.requirePlatformTenant(tenantId);
        long userId = support.nextId();
        long membershipId = support.nextId();
        authorizeRoleReplacement(tenantId, actor, membershipId, command.roleIds());
        String state = status(command.status());
        requireMembershipDepartment(tenantId, command.departmentId(), state);
        String username = command.username().trim().toLowerCase(Locale.ROOT);

        dsl.insertInto(IAM_USER)
            .set(IAM_USER.ID, userId)
            .set(IAM_USER.IDP_ISSUER, "local")
            .set(IAM_USER.IDP_SUBJECT, username)
            .set(IAM_USER.DISPLAY_NAME, command.name().trim())
            .set(IAM_USER.STATUS, PENDING_ACTIVATION)
            .set(IAM_USER.REMARK, JooqAdministrationSupport.blankToNull(command.remark()))
            .execute();
        dsl.insertInto(IAM_MEMBERSHIP)
            .set(IAM_MEMBERSHIP.ID, membershipId)
            .set(IAM_MEMBERSHIP.TENANT_ID, tenantId)
            .set(IAM_MEMBERSHIP.USER_ID, userId)
            .set(IAM_MEMBERSHIP.DEPARTMENT_ID, command.departmentId())
            .set(IAM_MEMBERSHIP.STATUS, state)
            .execute();
        dsl.insertInto(IAM_AUTHENTICATION_CREDENTIAL)
            .set(IAM_AUTHENTICATION_CREDENTIAL.USER_ID, userId)
            .set(IAM_AUTHENTICATION_CREDENTIAL.USERNAME, username)
            .set(IAM_AUTHENTICATION_CREDENTIAL.STATUS, DISABLED)
            .execute();
        replaceRoles(tenantId, membershipId, command.roleIds(), actor.membershipId());
        support.audit(tenantId, actor.membershipId(), "USER", userId, "CREATE", "user:create");
        return userId;
    }

    @Override
    @Transactional
    public void updateUser(long tenantId, AdministrationActor actor, long userId,
                           IdentityModels.MembershipUpdateCommand command) {
        support.requirePlatformTenant(tenantId);
        Long membershipId = findMembershipId(tenantId, userId);
        if (membershipId == null) {
            throw notFound("User");
        }
        authorizeRoleReplacement(tenantId, actor, membershipId, command.roleIds());
        String state = status(command.status());
        requireMembershipDepartment(tenantId, command.departmentId(), state);
        if (command.status() == 0) {
            protectAdministratorDeactivation(tenantId, actor, userId);
        }
        int updated = dsl.update(IAM_MEMBERSHIP)
            .set(IAM_MEMBERSHIP.DEPARTMENT_ID, command.departmentId())
            .set(IAM_MEMBERSHIP.STATUS, state)
            .set(IAM_MEMBERSHIP.PERMISSION_VERSION, IAM_MEMBERSHIP.PERMISSION_VERSION.plus(1L))
            .set(IAM_MEMBERSHIP.SESSION_VERSION, IAM_MEMBERSHIP.SESSION_VERSION.plus(1L))
            .set(IAM_MEMBERSHIP.ROW_VERSION, IAM_MEMBERSHIP.ROW_VERSION.plus(1L))
            .set(IAM_MEMBERSHIP.UPDATED_AT, DSL.currentOffsetDateTime())
            .where(IAM_MEMBERSHIP.TENANT_ID.eq(tenantId)
                .and(IAM_MEMBERSHIP.USER_ID.eq(userId))
                .and(IAM_MEMBERSHIP.ROW_VERSION.eq(command.userVersion()))
                .and(IAM_MEMBERSHIP.STATUS.ne(TERMINATED)))
            .execute();
        if (updated != 1) {
            throw new IdentityAdministrationService.OptimisticLockException();
        }
        replaceRoles(tenantId, membershipId, command.roleIds(), actor.membershipId());
        support.audit(tenantId, actor.membershipId(), "MEMBERSHIP", membershipId, "UPDATE", "user:update");
    }

    @Override
    @Transactional
    public long updateUserStatus(long tenantId, AdministrationActor actor, long userId,
                                 int newStatus, long expectedVersion) {
        support.requirePlatformTenant(tenantId);
        support.lockTenant(tenantId, actor);
        String state = status(newStatus);
        if (!currentMembershipDepartmentAllowsStatus(tenantId, userId, state)) {
            throw new IdentityAdministrationService.DataConflictException(
                "Membership department does not allow the requested status");
        }
        if (newStatus == 0) {
            protectAdministratorDeactivation(tenantId, actor, userId);
        }
        int updated = dsl.update(IAM_MEMBERSHIP)
            .set(IAM_MEMBERSHIP.STATUS, state)
            .set(IAM_MEMBERSHIP.PERMISSION_VERSION, IAM_MEMBERSHIP.PERMISSION_VERSION.plus(1L))
            .set(IAM_MEMBERSHIP.SESSION_VERSION, IAM_MEMBERSHIP.SESSION_VERSION.plus(1L))
            .set(IAM_MEMBERSHIP.ROW_VERSION, IAM_MEMBERSHIP.ROW_VERSION.plus(1L))
            .set(IAM_MEMBERSHIP.UPDATED_AT, DSL.currentOffsetDateTime())
            .where(IAM_MEMBERSHIP.TENANT_ID.eq(tenantId)
                .and(IAM_MEMBERSHIP.USER_ID.eq(userId))
                .and(IAM_MEMBERSHIP.ROW_VERSION.eq(expectedVersion))
                .and(IAM_MEMBERSHIP.STATUS.ne(TERMINATED)))
            .execute();
        if (updated != 1) {
            throw new IdentityAdministrationService.OptimisticLockException();
        }
        support.audit(tenantId, actor.membershipId(), "USER", userId, "STATUS", "user:disable");
        Long currentVersion = dsl.select(IAM_MEMBERSHIP.ROW_VERSION)
            .from(IAM_MEMBERSHIP)
            .where(IAM_MEMBERSHIP.TENANT_ID.eq(tenantId)
                .and(IAM_MEMBERSHIP.USER_ID.eq(userId)))
            .fetchOne(IAM_MEMBERSHIP.ROW_VERSION);
        if (currentVersion == null) {
            throw notFound("User");
        }
        return currentVersion;
    }

    @Override
    @Transactional
    public void deleteUser(long tenantId, AdministrationActor actor, long userId, long expectedVersion) {
        support.requirePlatformTenant(tenantId);
        protectAdministratorDeactivation(tenantId, actor, userId);
        int updated = dsl.update(IAM_MEMBERSHIP)
            .set(IAM_MEMBERSHIP.STATUS, TERMINATED)
            .set(IAM_MEMBERSHIP.PERMISSION_VERSION, IAM_MEMBERSHIP.PERMISSION_VERSION.plus(1L))
            .set(IAM_MEMBERSHIP.SESSION_VERSION, IAM_MEMBERSHIP.SESSION_VERSION.plus(1L))
            .set(IAM_MEMBERSHIP.ROW_VERSION, IAM_MEMBERSHIP.ROW_VERSION.plus(1L))
            .set(IAM_MEMBERSHIP.UPDATED_AT, DSL.currentOffsetDateTime())
            .where(IAM_MEMBERSHIP.TENANT_ID.eq(tenantId)
                .and(IAM_MEMBERSHIP.USER_ID.eq(userId))
                .and(IAM_MEMBERSHIP.ROW_VERSION.eq(expectedVersion))
                .and(IAM_MEMBERSHIP.STATUS.ne(TERMINATED)))
            .execute();
        requireSuccessfulDelete(updated, tenantId, userId);
        support.audit(tenantId, actor.membershipId(), "USER", userId, "DELETE", "user:delete");
    }

    private void requireSuccessfulDelete(int updated, long tenantId, long userId) {
        if (updated == 1) {
            return;
        }
        Long currentVersion = dsl.select(IAM_MEMBERSHIP.ROW_VERSION)
            .from(IAM_MEMBERSHIP)
            .where(IAM_MEMBERSHIP.TENANT_ID.eq(tenantId)
                .and(IAM_MEMBERSHIP.USER_ID.eq(userId))
                .and(IAM_MEMBERSHIP.STATUS.ne(TERMINATED)))
            .fetchOne(IAM_MEMBERSHIP.ROW_VERSION);
        if (currentVersion == null) {
            throw notFound("User");
        }
        throw new IdentityAdministrationService.OptimisticLockException();
    }

    private void authorizeRoleReplacement(long tenantId, AdministrationActor actor,
                                          long targetMembershipId, List<Long> requestedRoleIds) {
        support.lockTenant(tenantId, actor);
        validateRoles(tenantId, requestedRoleIds);
        Set<Long> currentRoleIds = Set.copyOf(findMembershipRoleIds(tenantId, targetMembershipId));
        Map<Long, RoleAssignmentPolicy.RoleFacts> roleFacts = new HashMap<>();
        dsl.select(IAM_ROLE.ID, IAM_ROLE.ASSIGNABLE, IAM_ROLE.SYSTEM_ROLE, IAM_ROLE.STATUS)
            .from(IAM_ROLE)
            .where(IAM_ROLE.TENANT_ID.eq(tenantId))
            .forEach(row -> roleFacts.put(row.get(IAM_ROLE.ID), new RoleAssignmentPolicy.RoleFacts(
                row.get(IAM_ROLE.ID),
                Boolean.TRUE.equals(row.get(IAM_ROLE.ASSIGNABLE)),
                Boolean.TRUE.equals(row.get(IAM_ROLE.SYSTEM_ROLE)),
                ACTIVE.equals(row.get(IAM_ROLE.STATUS)))));
        roleAssignmentPolicy.validateReplacement(
            actor.membershipId(),
            targetMembershipId,
            currentRoleIds,
            Set.copyOf(requestedRoleIds),
            Set.copyOf(findMembershipRoleIds(tenantId, actor.membershipId())),
            roleFacts);
    }

    private void protectAdministratorDeactivation(long tenantId, AdministrationActor actor,
                                                  long userId) {
        support.lockTenant(tenantId, actor);
        Long membershipId = findMembershipId(tenantId, userId);
        if (membershipId == null) {
            throw notFound("User");
        }
        boolean targetHasSystemRole = hasLoginCapableSystemRole(tenantId,
            IAM_MEMBERSHIP.ID.eq(membershipId));
        boolean anotherSystemAdministrator = hasLoginCapableSystemRole(tenantId,
            IAM_MEMBERSHIP.ID.ne(membershipId));
        roleAssignmentPolicy.validateDeactivation(targetHasSystemRole, anotherSystemAdministrator);
    }

    private boolean hasLoginCapableSystemRole(long tenantId, Condition membershipCondition) {
        return dsl.selectDistinct(IAM_AUTHENTICATION_CREDENTIAL.PASSWORD_HASH)
            .from(IAM_MEMBERSHIP)
            .join(IAM_USER)
                .on(IAM_USER.ID.eq(IAM_MEMBERSHIP.USER_ID)
                    .and(IAM_USER.STATUS.eq(ACTIVE)))
            .join(IAM_AUTHENTICATION_CREDENTIAL)
                .on(IAM_AUTHENTICATION_CREDENTIAL.USER_ID.eq(IAM_USER.ID)
                    .and(IAM_AUTHENTICATION_CREDENTIAL.STATUS.eq(ACTIVE)))
            .join(IAM_MEMBERSHIP_ROLE)
                .on(IAM_MEMBERSHIP_ROLE.TENANT_ID.eq(IAM_MEMBERSHIP.TENANT_ID)
                    .and(IAM_MEMBERSHIP_ROLE.MEMBERSHIP_ID.eq(IAM_MEMBERSHIP.ID)))
            .join(IAM_ROLE)
                .on(IAM_ROLE.TENANT_ID.eq(IAM_MEMBERSHIP_ROLE.TENANT_ID)
                    .and(IAM_ROLE.ID.eq(IAM_MEMBERSHIP_ROLE.ROLE_ID)))
            .where(IAM_MEMBERSHIP.TENANT_ID.eq(tenantId)
                .and(membershipCondition)
                .and(IAM_MEMBERSHIP.STATUS.eq(ACTIVE))
                .and(IAM_ROLE.SYSTEM_ROLE.isTrue())
                .and(IAM_ROLE.STATUS.eq(ACTIVE)))
            .fetch(IAM_AUTHENTICATION_CREDENTIAL.PASSWORD_HASH)
            .stream()
            .anyMatch(LoginCredentialPolicy::isLoginCapableHash);
    }

    private void requireMembershipDepartment(long tenantId, long departmentId, String membershipStatus) {
        var condition = IAM_DEPARTMENT.TENANT_ID.eq(tenantId).and(IAM_DEPARTMENT.ID.eq(departmentId));
        if (ACTIVE.equals(membershipStatus)) {
            condition = condition.and(IAM_DEPARTMENT.STATUS.eq(ACTIVE));
        }
        if (!dsl.fetchExists(dsl.selectOne().from(IAM_DEPARTMENT).where(condition))) {
            throw new IdentityAdministrationService.DataConflictException(
                "Membership department does not allow the requested status");
        }
    }

    private boolean currentMembershipDepartmentAllowsStatus(long tenantId, long userId, String membershipStatus) {
        var condition = IAM_MEMBERSHIP.TENANT_ID.eq(tenantId)
            .and(IAM_MEMBERSHIP.USER_ID.eq(userId))
            .and(IAM_MEMBERSHIP.STATUS.ne(TERMINATED));
        if (ACTIVE.equals(membershipStatus)) {
            condition = condition.and(IAM_DEPARTMENT.STATUS.eq(ACTIVE));
        }
        return dsl.fetchExists(dsl.selectOne()
            .from(IAM_MEMBERSHIP)
            .join(IAM_DEPARTMENT)
                .on(IAM_DEPARTMENT.TENANT_ID.eq(IAM_MEMBERSHIP.TENANT_ID)
                    .and(IAM_DEPARTMENT.ID.eq(IAM_MEMBERSHIP.DEPARTMENT_ID)))
            .where(condition));
    }

    private void validateRoles(long tenantId, List<Long> roleIds) {
        Set<Long> distinctRoleIds = new LinkedHashSet<>(roleIds);
        if (distinctRoleIds.isEmpty()) {
            return;
        }
        int found = dsl.fetchCount(IAM_ROLE,
            IAM_ROLE.TENANT_ID.eq(tenantId)
                .and(IAM_ROLE.ID.in(distinctRoleIds)));
        if (found != distinctRoleIds.size()) {
            throw new IdentityAdministrationService.ResourceNotFoundException(
                "One or more roles were not found");
        }
    }

    private Long findMembershipId(long tenantId, long userId) {
        return dsl.select(IAM_MEMBERSHIP.ID)
            .from(IAM_MEMBERSHIP)
            .where(IAM_MEMBERSHIP.TENANT_ID.eq(tenantId)
                .and(IAM_MEMBERSHIP.USER_ID.eq(userId))
                .and(IAM_MEMBERSHIP.STATUS.ne(TERMINATED)))
            .fetchOne(IAM_MEMBERSHIP.ID);
    }

    private List<Long> findMembershipRoleIds(long tenantId, long membershipId) {
        return dsl.select(IAM_MEMBERSHIP_ROLE.ROLE_ID)
            .from(IAM_MEMBERSHIP_ROLE)
            .where(IAM_MEMBERSHIP_ROLE.TENANT_ID.eq(tenantId)
                .and(IAM_MEMBERSHIP_ROLE.MEMBERSHIP_ID.eq(membershipId)))
            .orderBy(IAM_MEMBERSHIP_ROLE.ROLE_ID)
            .fetch(IAM_MEMBERSHIP_ROLE.ROLE_ID);
    }

    private void replaceRoles(long tenantId, long membershipId, List<Long> roleIds,
                              long operatorMembershipId) {
        dsl.deleteFrom(IAM_MEMBERSHIP_ROLE)
            .where(IAM_MEMBERSHIP_ROLE.TENANT_ID.eq(tenantId)
                .and(IAM_MEMBERSHIP_ROLE.MEMBERSHIP_ID.eq(membershipId)))
            .execute();
        Set<Long> distinctRoleIds = new LinkedHashSet<>(roleIds);
        if (distinctRoleIds.isEmpty()) {
            return;
        }
        var insert = dsl.insertInto(IAM_MEMBERSHIP_ROLE,
            IAM_MEMBERSHIP_ROLE.TENANT_ID,
            IAM_MEMBERSHIP_ROLE.MEMBERSHIP_ID,
            IAM_MEMBERSHIP_ROLE.ROLE_ID,
            IAM_MEMBERSHIP_ROLE.ASSIGNED_BY);
        distinctRoleIds.forEach(roleId -> insert.values(
            tenantId, membershipId, roleId, operatorMembershipId));
        insert.execute();
    }
}
