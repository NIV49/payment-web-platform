package com.niv.payment.permission.persistence.repository;

import com.niv.payment.permission.service.IdentityAdministrationService;
import com.niv.payment.permission.service.RoleAssignmentPolicy;
import com.niv.payment.permission.service.RoleConfigurationCommand;
import com.niv.payment.permission.service.RoleConfigurationCreateCommand;
import com.niv.payment.permission.service.RoleConfigurationModels;
import com.niv.payment.permission.service.RoleConfigurationPort;
import com.niv.payment.permission.service.RoleGrantAdministrationService;
import com.niv.payment.permission.service.RoleGrantModels;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.impl.DSL;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_AUDIT_EVENT;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_MEMBERSHIP;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_MEMBERSHIP_ROLE;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_MENU;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_PERMISSION_CHANGE_OUTBOX;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_ROLE;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_ROLE_MENU;
import static com.niv.payment.permission.persistence.repository.JooqAdministrationSupport.notFound;
import static com.niv.payment.permission.persistence.repository.JooqAdministrationSupport.status;

/** One transaction for role attributes, navigation visibility, and effective grants. */
public class JooqRoleConfigurationRepository implements RoleConfigurationPort {
    private static final Set<String> REQUIRED_TRANSACTIONAL_PERMISSIONS = Set.of(
        "role:view", "role:update", "menu:view",
        RoleGrantAdministrationService.GRANT_UPDATE_PERMISSION);
    private static final Set<String> REQUIRED_CREATE_PERMISSIONS = Set.of(
        "role:view", "role:create", "menu:view",
        RoleGrantAdministrationService.GRANT_UPDATE_PERMISSION);
    private static final Set<String> ROUTABLE_MENU_TYPES =
        Set.of("DIRECTORY", "PAGE", "EMBEDDED", "LINK");

    private final DSLContext dsl;
    private final JooqRoleGrantAdministrationRepository grants;
    private final JooqAdministrationSupport support;
    private final Supplier<String> traceIdSupplier;

    public JooqRoleConfigurationRepository(
        DSLContext dsl,
        JooqRoleGrantAdministrationRepository grants,
        Supplier<String> traceIdSupplier) {
        this.dsl = Objects.requireNonNull(dsl, "dsl");
        this.grants = Objects.requireNonNull(grants, "grants");
        this.traceIdSupplier = Objects.requireNonNull(traceIdSupplier, "traceIdSupplier");
        this.support = new JooqAdministrationSupport(dsl, traceIdSupplier);
    }

    @Override
    @Transactional
    public RoleConfigurationModels.RoleConfiguration createAtomically(
        RoleConfigurationCreateCommand command) {
        support.requirePlatformTenant(command.tenantId());
        support.lockTenant(command.tenantId(), command.actor());
        grants.requireSystemActor(command.tenantId(), command.actor().membershipId(), true);
        grants.requireTransactionalPermissions(
            command.tenantId(), command.actor().membershipId(), REQUIRED_CREATE_PERMISSIONS);

        Set<Long> menuIds = validatedMenus(command.tenantId(), command.menuIds());
        Map<String, Long> permissionIds = grants.requireCatalog(command.grants());
        long roleId = support.nextId();
        dsl.insertInto(IAM_ROLE)
            .set(IAM_ROLE.ID, roleId)
            .set(IAM_ROLE.TENANT_ID, command.tenantId())
            .set(IAM_ROLE.ROLE_CODE, slug(command.name()) + '-' + roleId)
            .set(IAM_ROLE.ROLE_NAME, command.name())
            .set(IAM_ROLE.APPLICABLE_TENANT_TYPE, "PLATFORM")
            .set(IAM_ROLE.ASSIGNABLE, true)
            .set(IAM_ROLE.SYSTEM_ROLE, false)
            .set(IAM_ROLE.STATUS, status(command.status()))
            .set(IAM_ROLE.REMARK, JooqAdministrationSupport.blankToNull(command.remark()))
            .execute();
        replaceMenus(command.tenantId(), roleId, menuIds);
        grants.replaceGrantRows(
            command.tenantId(), roleId, command.actor().membershipId(),
            command.grants(), permissionIds);
        appendCreateAuditAndOutbox(command, roleId, menuIds);
        return new RoleConfigurationModels.RoleConfiguration(
            roleId, 0L, List.copyOf(menuIds), command.grants(), true);
    }

    @Override
    @Transactional
    public RoleConfigurationModels.RoleConfiguration replaceAtomically(
        RoleConfigurationCommand command) {
        support.lockTenant(command.tenantId(), command.actor());
        var role = dsl.select(
                IAM_ROLE.ROW_VERSION, IAM_ROLE.ROLE_NAME, IAM_ROLE.STATUS, IAM_ROLE.REMARK,
                IAM_ROLE.SYSTEM_ROLE, IAM_ROLE.ASSIGNABLE)
            .from(IAM_ROLE)
            .where(IAM_ROLE.TENANT_ID.eq(command.tenantId())
                .and(IAM_ROLE.ID.eq(command.roleId()))
                .and(IAM_ROLE.DELETED_AT.isNull()))
            .forUpdate()
            .fetchOne();
        if (role == null) {
            throw notFound("Role");
        }
        grants.requireSystemActor(command.tenantId(), command.actor().membershipId(), true);
        grants.requireTransactionalPermissions(
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

        RoleGrantModels.RoleGrants existingGrants =
            grants.loadRoleGrants(command.tenantId(), command.roleId());
        if (!existingGrants.editable()) {
            throw new IdentityAdministrationService.DataConflictException(
                "Role contains grants outside the supported administration surface");
        }
        Set<Long> menuIds = validatedMenus(command.tenantId(), command.menuIds());
        Map<String, Long> permissionIds = grants.requireCatalog(command.grants());
        List<Long> existingMenus = dsl.select(IAM_ROLE_MENU.MENU_ID)
            .from(IAM_ROLE_MENU)
            .join(IAM_MENU)
            .on(IAM_MENU.TENANT_ID.eq(IAM_ROLE_MENU.TENANT_ID)
                .and(IAM_MENU.ID.eq(IAM_ROLE_MENU.MENU_ID)))
            .where(IAM_ROLE_MENU.TENANT_ID.eq(command.tenantId())
                .and(IAM_ROLE_MENU.ROLE_ID.eq(command.roleId()))
                .and(IAM_MENU.STATUS.eq("ACTIVE"))
                .and(IAM_MENU.DELETED_AT.isNull())
                .and(IAM_MENU.MENU_TYPE.in(ROUTABLE_MENU_TYPES)))
            .orderBy(IAM_ROLE_MENU.MENU_ID)
            .fetch(IAM_ROLE_MENU.MENU_ID);
        Field<JSONB> before = auditValue(
            currentVersion, role.get(IAM_ROLE.ROLE_NAME), role.get(IAM_ROLE.STATUS),
            existingMenus, existingGrants.grants(), command.reason());

        long nextVersion = currentVersion + 1L;
        int roleUpdated = dsl.update(IAM_ROLE)
            .set(IAM_ROLE.ROLE_NAME, command.name())
            .set(IAM_ROLE.STATUS, status(command.status()))
            .set(IAM_ROLE.REMARK, JooqAdministrationSupport.blankToNull(command.remark()))
            .set(IAM_ROLE.UPDATED_AT, DSL.currentOffsetDateTime())
            .set(IAM_ROLE.ROW_VERSION, nextVersion)
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
        replaceMenus(command.tenantId(), command.roleId(), menuIds);
        grants.replaceGrantRows(
            command.tenantId(), command.roleId(), command.actor().membershipId(),
            command.grants(), permissionIds);
        bumpRoleMembers(command.tenantId(), command.roleId());

        Field<JSONB> after = auditValue(
            nextVersion, command.name(), status(command.status()),
            List.copyOf(menuIds), command.grants(), command.reason());
        appendAuditAndOutbox(command, nextVersion, before, after);
        return new RoleConfigurationModels.RoleConfiguration(
            command.roleId(), nextVersion, List.copyOf(menuIds), command.grants(), true);
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
        var insert = dsl.insertInto(
            IAM_ROLE_MENU, IAM_ROLE_MENU.TENANT_ID, IAM_ROLE_MENU.ROLE_ID, IAM_ROLE_MENU.MENU_ID);
        menuIds.forEach(menuId -> insert.values(tenantId, roleId, menuId));
        insert.execute();
    }

    private void bumpRoleMembers(long tenantId, long roleId) {
        dsl.update(IAM_MEMBERSHIP)
            .set(IAM_MEMBERSHIP.PERMISSION_VERSION, IAM_MEMBERSHIP.PERMISSION_VERSION.plus(1L))
            .set(IAM_MEMBERSHIP.UPDATED_AT, DSL.currentOffsetDateTime())
            .where(IAM_MEMBERSHIP.TENANT_ID.eq(tenantId)
                .andExists(dsl.selectOne().from(IAM_MEMBERSHIP_ROLE)
                    .where(IAM_MEMBERSHIP_ROLE.TENANT_ID.eq(IAM_MEMBERSHIP.TENANT_ID)
                        .and(IAM_MEMBERSHIP_ROLE.MEMBERSHIP_ID.eq(IAM_MEMBERSHIP.ID))
                        .and(IAM_MEMBERSHIP_ROLE.ROLE_ID.eq(roleId)))))
            .execute();
    }

    private void appendAuditAndOutbox(RoleConfigurationCommand command, long nextVersion,
                                      Field<JSONB> before, Field<JSONB> after) {
        String traceId = traceIdSupplier.get();
        dsl.insertInto(IAM_AUDIT_EVENT)
            .set(IAM_AUDIT_EVENT.ID, support.nextId())
            .set(IAM_AUDIT_EVENT.TENANT_ID, command.tenantId())
            .set(IAM_AUDIT_EVENT.OPERATOR_MEMBERSHIP_ID, command.actor().membershipId())
            .set(IAM_AUDIT_EVENT.TARGET_TYPE, "ROLE_CONFIGURATION")
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
            .set(IAM_PERMISSION_CHANGE_OUTBOX.AGGREGATE_TYPE, "ROLE_CONFIGURATION")
            .set(IAM_PERMISSION_CHANGE_OUTBOX.AGGREGATE_REF, Long.toString(command.roleId()))
            .set(IAM_PERMISSION_CHANGE_OUTBOX.EVENT_TYPE, "ROLE_CONFIGURATION_REPLACED")
            .set(IAM_PERMISSION_CHANGE_OUTBOX.PAYLOAD, after)
            .set(IAM_PERMISSION_CHANGE_OUTBOX.AGGREGATE_VERSION, nextVersion)
            .set(IAM_PERMISSION_CHANGE_OUTBOX.SCHEMA_VERSION, 1)
            .set(IAM_PERMISSION_CHANGE_OUTBOX.PARTITION_KEY,
                command.tenantId() + ":" + command.roleId())
            .set(IAM_PERMISSION_CHANGE_OUTBOX.TRACE_ID, traceId)
            .execute();
    }

    private void appendCreateAuditAndOutbox(
        RoleConfigurationCreateCommand command,
        long roleId,
        Set<Long> menuIds) {
        Field<JSONB> after = auditValue(
            0L, command.name(), status(command.status()), List.copyOf(menuIds),
            command.grants(), "role creation");
        String traceId = traceIdSupplier.get();
        dsl.insertInto(IAM_AUDIT_EVENT)
            .set(IAM_AUDIT_EVENT.ID, support.nextId())
            .set(IAM_AUDIT_EVENT.TENANT_ID, command.tenantId())
            .set(IAM_AUDIT_EVENT.OPERATOR_MEMBERSHIP_ID, command.actor().membershipId())
            .set(IAM_AUDIT_EVENT.TARGET_TYPE, "ROLE_CONFIGURATION")
            .set(IAM_AUDIT_EVENT.TARGET_REF, Long.toString(roleId))
            .set(IAM_AUDIT_EVENT.ACTION_CODE, "CREATE")
            .set(IAM_AUDIT_EVENT.DECISION, "ALLOW")
            .set(IAM_AUDIT_EVENT.REASON_CODE, "AUTHORIZED")
            .set(IAM_AUDIT_EVENT.PERMISSION_CODE, "role:create")
            .set(IAM_AUDIT_EVENT.AFTER_VALUE, after)
            .set(IAM_AUDIT_EVENT.TRACE_ID, traceId)
            .execute();
        dsl.insertInto(IAM_PERMISSION_CHANGE_OUTBOX)
            .set(IAM_PERMISSION_CHANGE_OUTBOX.ID, support.nextId())
            .set(IAM_PERMISSION_CHANGE_OUTBOX.TENANT_ID, command.tenantId())
            .set(IAM_PERMISSION_CHANGE_OUTBOX.AGGREGATE_TYPE, "ROLE_CONFIGURATION")
            .set(IAM_PERMISSION_CHANGE_OUTBOX.AGGREGATE_REF, Long.toString(roleId))
            .set(IAM_PERMISSION_CHANGE_OUTBOX.EVENT_TYPE, "ROLE_CONFIGURATION_CREATED")
            .set(IAM_PERMISSION_CHANGE_OUTBOX.PAYLOAD, after)
            .set(IAM_PERMISSION_CHANGE_OUTBOX.AGGREGATE_VERSION, 0L)
            .set(IAM_PERMISSION_CHANGE_OUTBOX.SCHEMA_VERSION, 1)
            .set(IAM_PERMISSION_CHANGE_OUTBOX.PARTITION_KEY,
                command.tenantId() + ":" + roleId)
            .set(IAM_PERMISSION_CHANGE_OUTBOX.TRACE_ID, traceId)
            .execute();
    }

    private static Field<JSONB> auditValue(long roleVersion, String name, String roleStatus,
                                           List<Long> menuIds,
                                           List<RoleGrantModels.Selection> roleGrants,
                                           String reason) {
        String menus = menuIds.stream().map(String::valueOf).sorted()
            .collect(java.util.stream.Collectors.joining(","));
        String permissions = roleGrants.stream().map(item -> item.permission().value()).sorted()
            .collect(java.util.stream.Collectors.joining(","));
        return DSL.field(
            "jsonb_build_object('roleVersion', {0}, 'name', {1}, 'status', {2}, "
                + "'menuIds', {3}, 'permissions', {4}, 'reason', {5})",
            JSONB.class, DSL.val(roleVersion), DSL.val(name), DSL.val(roleStatus),
            DSL.val(menus), DSL.val(permissions), DSL.val(reason));
    }

    private static String slug(String value) {
        String slug = value.trim().toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("(^-|-$)", "");
        return slug.isBlank() ? "role" : slug;
    }
}
