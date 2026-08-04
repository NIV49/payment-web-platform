package com.niv.payment.permission.persistence.repository;

import com.niv.payment.permission.domain.AdministrationActor;
import com.niv.payment.permission.port.RoleAdministrationPort;
import com.niv.payment.permission.service.IdentityAdministrationService;
import com.niv.payment.permission.service.IdentityModels;
import com.niv.payment.permission.service.RoleAssignmentPolicy;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.impl.DSL;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_MEMBERSHIP;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_MEMBERSHIP_ROLE;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_MENU;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_AUDIT_EVENT;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_PERMISSION_CHANGE_OUTBOX;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_ROLE;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_ROLE_MENU;
import static com.niv.payment.permission.persistence.repository.JooqAdministrationSupport.DISABLED;
import static com.niv.payment.permission.persistence.repository.JooqAdministrationSupport.notFound;
import static com.niv.payment.permission.persistence.repository.JooqAdministrationSupport.roleCode;
import static com.niv.payment.permission.persistence.repository.JooqAdministrationSupport.status;

/** Role and presentation-menu administration. Business grants remain a separate capability. */
public class JooqRoleAdministrationRepository implements RoleAdministrationPort {
    private static final Set<String> ROUTABLE_MENU_TYPES =
        Set.of("DIRECTORY", "PAGE", "EMBEDDED", "LINK");

    private final DSLContext dsl;
    private final JooqIdentityQueryRepository queries;
    private final JooqRoleGrantAdministrationRepository grants;
    private final JooqAdministrationSupport support;
    private final Supplier<String> traceIdSupplier;

    public JooqRoleAdministrationRepository(DSLContext dsl,
                                            JooqIdentityQueryRepository queries,
                                            JooqRoleGrantAdministrationRepository grants,
                                            Supplier<String> traceIdSupplier) {
        this.dsl = Objects.requireNonNull(dsl, "dsl");
        this.queries = Objects.requireNonNull(queries, "queries");
        this.grants = Objects.requireNonNull(grants, "grants");
        this.traceIdSupplier = Objects.requireNonNull(traceIdSupplier, "traceIdSupplier");
        this.support = new JooqAdministrationSupport(dsl, traceIdSupplier);
    }

    @Override
    public IdentityModels.Page<IdentityModels.Role> findRoles(long tenantId, IdentityModels.RoleQuery query) {
        return queries.findRoles(tenantId, query);
    }

    @Override
    @Transactional
    public long createRole(long tenantId, AdministrationActor actor, IdentityModels.RoleCommand command) {
        support.requirePlatformTenant(tenantId);
        support.lockTenant(tenantId, actor);
        Set<Long> menuIds = validatedMenus(tenantId, command.menuIds());
        long roleId = support.nextId();
        dsl.insertInto(IAM_ROLE)
            .set(IAM_ROLE.ID, roleId)
            .set(IAM_ROLE.TENANT_ID, tenantId)
            .set(IAM_ROLE.ROLE_CODE, roleCode(command.name(), roleId))
            .set(IAM_ROLE.ROLE_NAME, command.name().trim())
            .set(IAM_ROLE.APPLICABLE_TENANT_TYPE, "PLATFORM")
            .set(IAM_ROLE.ASSIGNABLE, true)
            .set(IAM_ROLE.SYSTEM_ROLE, false)
            .set(IAM_ROLE.STATUS, status(command.status()))
            .set(IAM_ROLE.REMARK, JooqAdministrationSupport.blankToNull(command.remark()))
            .execute();
        grants.insertProtectedPortalGrant(tenantId, roleId, actor.membershipId());
        replaceMenus(tenantId, roleId, menuIds);
        support.audit(tenantId, actor.membershipId(), "ROLE", roleId, "CREATE", "role:create");
        return roleId;
    }

    @Override
    @Transactional
    public void updateRole(long tenantId, AdministrationActor actor, long roleId,
                           IdentityModels.RoleCommand command, long expectedVersion) {
        support.requirePlatformTenant(tenantId);
        support.lockTenant(tenantId, actor);
        requireOrdinaryRoleVersion(tenantId, roleId, expectedVersion);
        Set<Long> menuIds = validatedMenus(tenantId, command.menuIds());
        int updated = dsl.update(IAM_ROLE)
            .set(IAM_ROLE.ROLE_NAME, command.name().trim())
            .set(IAM_ROLE.STATUS, status(command.status()))
            .set(IAM_ROLE.REMARK, JooqAdministrationSupport.blankToNull(command.remark()))
            .set(IAM_ROLE.UPDATED_AT, DSL.currentOffsetDateTime())
            .set(IAM_ROLE.ROW_VERSION, IAM_ROLE.ROW_VERSION.plus(1L))
            .where(IAM_ROLE.TENANT_ID.eq(tenantId)
                .and(IAM_ROLE.ID.eq(roleId))
                .and(IAM_ROLE.ROW_VERSION.eq(expectedVersion))
                .and(IAM_ROLE.SYSTEM_ROLE.isFalse())
                .and(IAM_ROLE.ASSIGNABLE.isTrue())
                .and(IAM_ROLE.DELETED_AT.isNull()))
            .execute();
        requireSuccessfulMutation(updated);
        replaceMenus(tenantId, roleId, menuIds);
        bumpRoleMembers(tenantId, roleId);
        support.audit(tenantId, actor.membershipId(), "ROLE", roleId, "UPDATE", "role:update");
    }

    @Override
    @Transactional
    public void updateRoleStatus(long tenantId, AdministrationActor actor, long roleId,
                                 int newStatus, long expectedVersion) {
        support.requirePlatformTenant(tenantId);
        support.lockTenant(tenantId, actor);
        requireOrdinaryRoleVersion(tenantId, roleId, expectedVersion);
        int updated = dsl.update(IAM_ROLE)
            .set(IAM_ROLE.STATUS, status(newStatus))
            .set(IAM_ROLE.UPDATED_AT, DSL.currentOffsetDateTime())
            .set(IAM_ROLE.ROW_VERSION, IAM_ROLE.ROW_VERSION.plus(1L))
            .where(IAM_ROLE.TENANT_ID.eq(tenantId)
                .and(IAM_ROLE.ID.eq(roleId))
                .and(IAM_ROLE.ROW_VERSION.eq(expectedVersion))
                .and(IAM_ROLE.SYSTEM_ROLE.isFalse())
                .and(IAM_ROLE.ASSIGNABLE.isTrue())
                .and(IAM_ROLE.DELETED_AT.isNull()))
            .execute();
        requireSuccessfulMutation(updated);
        bumpRoleMembers(tenantId, roleId);
        support.audit(tenantId, actor.membershipId(), "ROLE", roleId, "STATUS", "role:update");
    }

    @Override
    @Transactional
    public void deleteRole(long tenantId, AdministrationActor actor, long roleId, long expectedVersion) {
        support.requirePlatformTenant(tenantId);
        support.lockTenant(tenantId, actor);
        requireOrdinaryRoleVersion(tenantId, roleId, expectedVersion);
        List<Long> affectedMembershipIds = dsl.select(IAM_MEMBERSHIP_ROLE.MEMBERSHIP_ID)
            .from(IAM_MEMBERSHIP_ROLE)
            .where(IAM_MEMBERSHIP_ROLE.TENANT_ID.eq(tenantId)
                .and(IAM_MEMBERSHIP_ROLE.ROLE_ID.eq(roleId)))
            .orderBy(IAM_MEMBERSHIP_ROLE.MEMBERSHIP_ID)
            .forUpdate()
            .fetch(IAM_MEMBERSHIP_ROLE.MEMBERSHIP_ID);
        int updated = dsl.update(IAM_ROLE)
            .set(IAM_ROLE.STATUS, DISABLED)
            .set(IAM_ROLE.DELETED_AT, DSL.currentOffsetDateTime())
            .set(IAM_ROLE.UPDATED_AT, DSL.currentOffsetDateTime())
            .set(IAM_ROLE.ROW_VERSION, IAM_ROLE.ROW_VERSION.plus(1L))
            .where(IAM_ROLE.TENANT_ID.eq(tenantId)
                .and(IAM_ROLE.ID.eq(roleId))
                .and(IAM_ROLE.ROW_VERSION.eq(expectedVersion))
                .and(IAM_ROLE.SYSTEM_ROLE.isFalse())
                .and(IAM_ROLE.ASSIGNABLE.isTrue())
                .and(IAM_ROLE.DELETED_AT.isNull()))
            .execute();
        requireSuccessfulMutation(updated);
        if (!affectedMembershipIds.isEmpty()) {
            dsl.update(IAM_MEMBERSHIP)
                .set(IAM_MEMBERSHIP.PERMISSION_VERSION, IAM_MEMBERSHIP.PERMISSION_VERSION.plus(1L))
                .set(IAM_MEMBERSHIP.UPDATED_AT, DSL.currentOffsetDateTime())
                .where(IAM_MEMBERSHIP.TENANT_ID.eq(tenantId)
                    .and(IAM_MEMBERSHIP.ID.in(affectedMembershipIds)))
                .execute();
        }
        dsl.deleteFrom(IAM_MEMBERSHIP_ROLE)
            .where(IAM_MEMBERSHIP_ROLE.TENANT_ID.eq(tenantId)
                .and(IAM_MEMBERSHIP_ROLE.ROLE_ID.eq(roleId)))
            .execute();
        appendDeleteAuditAndOutbox(
            tenantId, actor.membershipId(), roleId, expectedVersion + 1L, affectedMembershipIds);
    }

    private void replaceMenus(long tenantId, long roleId, Set<Long> menuIds) {
        dsl.deleteFrom(IAM_ROLE_MENU)
            .where(IAM_ROLE_MENU.TENANT_ID.eq(tenantId)
                .and(IAM_ROLE_MENU.ROLE_ID.eq(roleId))
                .and(IAM_ROLE_MENU.MENU_ID.in(
                    dsl.select(IAM_MENU.ID)
                        .from(IAM_MENU)
                        .where(IAM_MENU.TENANT_ID.eq(tenantId)
                            .and(IAM_MENU.STATUS.eq("ACTIVE"))
                            .and(IAM_MENU.DELETED_AT.isNull())
                            .and(IAM_MENU.MENU_TYPE.in(ROUTABLE_MENU_TYPES))))))
            .execute();
        if (menuIds.isEmpty()) {
            return;
        }
        var insert = dsl.insertInto(IAM_ROLE_MENU,
            IAM_ROLE_MENU.TENANT_ID,
            IAM_ROLE_MENU.ROLE_ID,
            IAM_ROLE_MENU.MENU_ID);
        menuIds.forEach(menuId -> insert.values(tenantId, roleId, menuId));
        insert.execute();
    }

    private void requireOrdinaryRoleVersion(long tenantId, long roleId, long expectedVersion) {
        var role = dsl.select(IAM_ROLE.ROW_VERSION, IAM_ROLE.SYSTEM_ROLE, IAM_ROLE.ASSIGNABLE)
            .from(IAM_ROLE)
            .where(IAM_ROLE.TENANT_ID.eq(tenantId)
                .and(IAM_ROLE.ID.eq(roleId))
                .and(IAM_ROLE.DELETED_AT.isNull()))
            .forUpdate()
            .fetchOne();
        if (role == null) {
            throw notFound("Role");
        }
        if (Boolean.TRUE.equals(role.get(IAM_ROLE.SYSTEM_ROLE))
            || !Boolean.TRUE.equals(role.get(IAM_ROLE.ASSIGNABLE))) {
            throw new RoleAssignmentPolicy.RoleNotAssignableException();
        }
        if (role.get(IAM_ROLE.ROW_VERSION) != expectedVersion) {
            throw new IdentityAdministrationService.OptimisticLockException();
        }
    }

    private void requireSuccessfulMutation(int updated) {
        if (updated != 1) {
            throw new IdentityAdministrationService.OptimisticLockException();
        }
    }

    private Set<Long> validatedMenus(long tenantId, List<Long> requestedMenuIds) {
        Set<Long> menuIds = new LinkedHashSet<>(requestedMenuIds);
        if (menuIds.isEmpty()) {
            return menuIds;
        }
        var menus = dsl.select(IAM_MENU.ID, IAM_MENU.MENU_TYPE, IAM_MENU.STATUS)
            .from(IAM_MENU)
            .where(IAM_MENU.TENANT_ID.eq(tenantId)
                .and(IAM_MENU.ID.in(menuIds))
                .and(IAM_MENU.DELETED_AT.isNull()))
            .forUpdate()
            .fetch();
        if (menus.size() != menuIds.size()) {
            throw notFound("Menu");
        }
        boolean invalidMenu = menus.stream().anyMatch(menu ->
            !"ACTIVE".equals(menu.get(IAM_MENU.STATUS))
                || !ROUTABLE_MENU_TYPES.contains(menu.get(IAM_MENU.MENU_TYPE)));
        if (invalidMenu) {
            throw new IdentityAdministrationService.DataConflictException(
                "Role menus must be active routable menus");
        }
        return menuIds;
    }

    private void bumpRoleMembers(long tenantId, long roleId) {
        dsl.update(IAM_MEMBERSHIP)
            .set(IAM_MEMBERSHIP.PERMISSION_VERSION, IAM_MEMBERSHIP.PERMISSION_VERSION.plus(1L))
            .set(IAM_MEMBERSHIP.UPDATED_AT, DSL.currentOffsetDateTime())
            .where(IAM_MEMBERSHIP.TENANT_ID.eq(tenantId)
                .andExists(dsl.selectOne()
                    .from(IAM_MEMBERSHIP_ROLE)
                    .where(IAM_MEMBERSHIP_ROLE.TENANT_ID.eq(IAM_MEMBERSHIP.TENANT_ID)
                        .and(IAM_MEMBERSHIP_ROLE.MEMBERSHIP_ID.eq(IAM_MEMBERSHIP.ID))
                        .and(IAM_MEMBERSHIP_ROLE.ROLE_ID.eq(roleId)))))
            .execute();
    }

    private void appendDeleteAuditAndOutbox(long tenantId, long actorMembershipId,
                                            long roleId, long roleVersion,
                                            List<Long> affectedMembershipIds) {
        String membershipIds = affectedMembershipIds.stream().map(String::valueOf)
            .collect(java.util.stream.Collectors.joining(","));
        Field<JSONB> payload = DSL.field(
            "jsonb_build_object('roleVersion', {0}, 'membershipIds', {1})",
            JSONB.class, DSL.val(roleVersion), DSL.val(membershipIds));
        String traceId = traceIdSupplier.get();
        dsl.insertInto(IAM_AUDIT_EVENT)
            .set(IAM_AUDIT_EVENT.ID, support.nextId())
            .set(IAM_AUDIT_EVENT.TENANT_ID, tenantId)
            .set(IAM_AUDIT_EVENT.OPERATOR_MEMBERSHIP_ID, actorMembershipId)
            .set(IAM_AUDIT_EVENT.TARGET_TYPE, "ROLE")
            .set(IAM_AUDIT_EVENT.TARGET_REF, Long.toString(roleId))
            .set(IAM_AUDIT_EVENT.ACTION_CODE, "DELETE")
            .set(IAM_AUDIT_EVENT.DECISION, "ALLOW")
            .set(IAM_AUDIT_EVENT.REASON_CODE, "AUTHORIZED")
            .set(IAM_AUDIT_EVENT.PERMISSION_CODE, "role:delete")
            .set(IAM_AUDIT_EVENT.AFTER_VALUE, payload)
            .set(IAM_AUDIT_EVENT.TRACE_ID, traceId)
            .execute();
        dsl.insertInto(IAM_PERMISSION_CHANGE_OUTBOX)
            .set(IAM_PERMISSION_CHANGE_OUTBOX.ID, support.nextId())
            .set(IAM_PERMISSION_CHANGE_OUTBOX.TENANT_ID, tenantId)
            .set(IAM_PERMISSION_CHANGE_OUTBOX.AGGREGATE_TYPE, "ROLE")
            .set(IAM_PERMISSION_CHANGE_OUTBOX.AGGREGATE_REF, Long.toString(roleId))
            .set(IAM_PERMISSION_CHANGE_OUTBOX.EVENT_TYPE, "ROLE_DELETED")
            .set(IAM_PERMISSION_CHANGE_OUTBOX.PAYLOAD, payload)
            .set(IAM_PERMISSION_CHANGE_OUTBOX.AGGREGATE_VERSION, roleVersion)
            .set(IAM_PERMISSION_CHANGE_OUTBOX.SCHEMA_VERSION, 1)
            .set(IAM_PERMISSION_CHANGE_OUTBOX.PARTITION_KEY, tenantId + ":" + roleId)
            .set(IAM_PERMISSION_CHANGE_OUTBOX.TRACE_ID, traceId)
            .execute();
    }

}
