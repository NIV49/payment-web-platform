package com.niv.payment.permission.persistence.repository;

import com.niv.payment.permission.domain.AdministrationActor;
import com.niv.payment.permission.port.RoleAdministrationPort;
import com.niv.payment.permission.service.IdentityAdministrationService;
import com.niv.payment.permission.service.IdentityModels;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_MEMBERSHIP;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_MEMBERSHIP_ROLE;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_MENU;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_ROLE;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_ROLE_MENU;
import static com.niv.payment.permission.persistence.repository.JooqAdministrationSupport.DISABLED;
import static com.niv.payment.permission.persistence.repository.JooqAdministrationSupport.notFound;
import static com.niv.payment.permission.persistence.repository.JooqAdministrationSupport.status;

/** Role and presentation-menu administration. Business grants remain a separate capability. */
public class JooqRoleAdministrationRepository implements RoleAdministrationPort {
    private final DSLContext dsl;
    private final JooqIdentityQueryRepository queries;
    private final JooqAdministrationSupport support;

    public JooqRoleAdministrationRepository(DSLContext dsl,
                                            JooqIdentityQueryRepository queries,
                                            Supplier<String> traceIdSupplier) {
        this.dsl = Objects.requireNonNull(dsl, "dsl");
        this.queries = Objects.requireNonNull(queries, "queries");
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
        long roleId = support.nextId();
        dsl.insertInto(IAM_ROLE)
            .set(IAM_ROLE.ID, roleId)
            .set(IAM_ROLE.TENANT_ID, tenantId)
            .set(IAM_ROLE.ROLE_CODE, slug(command.name()) + '-' + roleId)
            .set(IAM_ROLE.ROLE_NAME, command.name().trim())
            .set(IAM_ROLE.APPLICABLE_TENANT_TYPE, "PLATFORM")
            .set(IAM_ROLE.ASSIGNABLE, true)
            .set(IAM_ROLE.SYSTEM_ROLE, false)
            .set(IAM_ROLE.STATUS, status(command.status()))
            .set(IAM_ROLE.REMARK, JooqAdministrationSupport.blankToNull(command.remark()))
            .execute();
        replaceMenus(tenantId, roleId, command.menuIds());
        support.audit(tenantId, actor.membershipId(), "ROLE", roleId, "CREATE", "role:create");
        return roleId;
    }

    @Override
    @Transactional
    public void updateRole(long tenantId, AdministrationActor actor, long roleId,
                           IdentityModels.RoleCommand command, long expectedVersion) {
        support.requirePlatformTenant(tenantId);
        support.lockTenant(tenantId, actor);
        int updated = dsl.update(IAM_ROLE)
            .set(IAM_ROLE.ROLE_NAME, command.name().trim())
            .set(IAM_ROLE.STATUS, status(command.status()))
            .set(IAM_ROLE.REMARK, JooqAdministrationSupport.blankToNull(command.remark()))
            .set(IAM_ROLE.UPDATED_AT, DSL.currentOffsetDateTime())
            .set(IAM_ROLE.ROW_VERSION, IAM_ROLE.ROW_VERSION.plus(1L))
            .where(IAM_ROLE.TENANT_ID.eq(tenantId)
                .and(IAM_ROLE.ID.eq(roleId))
                .and(IAM_ROLE.ROW_VERSION.eq(expectedVersion))
                .and(IAM_ROLE.SYSTEM_ROLE.isFalse()))
            .execute();
        requireSuccessfulMutation(updated, tenantId, roleId);
        replaceMenus(tenantId, roleId, command.menuIds());
        bumpRoleMembers(tenantId, roleId);
        support.audit(tenantId, actor.membershipId(), "ROLE", roleId, "UPDATE", "role:update");
    }

    @Override
    @Transactional
    public void updateRoleStatus(long tenantId, AdministrationActor actor, long roleId,
                                 int newStatus, long expectedVersion) {
        support.requirePlatformTenant(tenantId);
        support.lockTenant(tenantId, actor);
        int updated = dsl.update(IAM_ROLE)
            .set(IAM_ROLE.STATUS, status(newStatus))
            .set(IAM_ROLE.UPDATED_AT, DSL.currentOffsetDateTime())
            .set(IAM_ROLE.ROW_VERSION, IAM_ROLE.ROW_VERSION.plus(1L))
            .where(IAM_ROLE.TENANT_ID.eq(tenantId)
                .and(IAM_ROLE.ID.eq(roleId))
                .and(IAM_ROLE.ROW_VERSION.eq(expectedVersion))
                .and(IAM_ROLE.SYSTEM_ROLE.isFalse()))
            .execute();
        requireSuccessfulMutation(updated, tenantId, roleId);
        bumpRoleMembers(tenantId, roleId);
        support.audit(tenantId, actor.membershipId(), "ROLE", roleId, "STATUS", "role:update");
    }

    @Override
    @Transactional
    public void deleteRole(long tenantId, AdministrationActor actor, long roleId, long expectedVersion) {
        support.requirePlatformTenant(tenantId);
        support.lockTenant(tenantId, actor);
        int updated = dsl.update(IAM_ROLE)
            .set(IAM_ROLE.STATUS, DISABLED)
            .set(IAM_ROLE.UPDATED_AT, DSL.currentOffsetDateTime())
            .set(IAM_ROLE.ROW_VERSION, IAM_ROLE.ROW_VERSION.plus(1L))
            .where(IAM_ROLE.TENANT_ID.eq(tenantId)
                .and(IAM_ROLE.ID.eq(roleId))
                .and(IAM_ROLE.ROW_VERSION.eq(expectedVersion))
                .and(IAM_ROLE.SYSTEM_ROLE.isFalse()))
            .execute();
        requireSuccessfulMutation(updated, tenantId, roleId);
        bumpRoleMembers(tenantId, roleId);
        support.audit(tenantId, actor.membershipId(), "ROLE", roleId, "DELETE", "role:delete");
    }

    private void replaceMenus(long tenantId, long roleId, List<Long> menuIds) {
        Set<Long> distinctMenuIds = new LinkedHashSet<>(menuIds);
        validateMenus(tenantId, distinctMenuIds);
        dsl.deleteFrom(IAM_ROLE_MENU)
            .where(IAM_ROLE_MENU.TENANT_ID.eq(tenantId)
                .and(IAM_ROLE_MENU.ROLE_ID.eq(roleId)))
            .execute();
        if (distinctMenuIds.isEmpty()) {
            return;
        }
        var insert = dsl.insertInto(IAM_ROLE_MENU,
            IAM_ROLE_MENU.TENANT_ID,
            IAM_ROLE_MENU.ROLE_ID,
            IAM_ROLE_MENU.MENU_ID);
        distinctMenuIds.forEach(menuId -> insert.values(tenantId, roleId, menuId));
        insert.execute();
    }

    private void requireSuccessfulMutation(int updated, long tenantId, long roleId) {
        if (updated == 1) {
            return;
        }
        Long currentVersion = dsl.select(IAM_ROLE.ROW_VERSION)
            .from(IAM_ROLE)
            .where(IAM_ROLE.TENANT_ID.eq(tenantId)
                .and(IAM_ROLE.ID.eq(roleId))
                .and(IAM_ROLE.SYSTEM_ROLE.isFalse()))
            .fetchOne(IAM_ROLE.ROW_VERSION);
        if (currentVersion == null) {
            throw notFound("Role");
        }
        throw new IdentityAdministrationService.OptimisticLockException();
    }

    private void validateMenus(long tenantId, Set<Long> menuIds) {
        if (menuIds.isEmpty()) {
            return;
        }
        int found = dsl.fetchCount(IAM_MENU,
            IAM_MENU.TENANT_ID.eq(tenantId).and(IAM_MENU.ID.in(menuIds)));
        if (found != menuIds.size()) {
            throw notFound("Menu");
        }
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

    private static String slug(String value) {
        String slug = value.trim().toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("(^-|-$)", "");
        return slug.isBlank() ? "role" : slug;
    }
}
