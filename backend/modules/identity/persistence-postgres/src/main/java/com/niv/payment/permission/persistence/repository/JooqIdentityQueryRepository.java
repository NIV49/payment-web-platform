package com.niv.payment.permission.persistence.repository;

import com.niv.payment.permission.port.IdentityQueryPort;
import com.niv.payment.permission.service.IdentityAdministrationService;
import com.niv.payment.permission.service.IdentityModels;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.Record;
import org.jooq.impl.DSL;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_AUTHENTICATION_CREDENTIAL;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_DEPARTMENT;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_GRANT_DIMENSION;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_MEMBERSHIP;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_MEMBERSHIP_ROLE;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_MENU;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_PERMISSION;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_ROLE;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_ROLE_GRANT;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_ROLE_MENU;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_USER;
import static com.niv.payment.permission.service.IdentityAdministrationService.MAX_TREE_NODES;

/** Read-model adapter for the Vben identity administration contract. */
public final class JooqIdentityQueryRepository implements IdentityQueryPort {
    private static final String ACTIVE = "ACTIVE";
    private static final String TERMINATED = "TERMINATED";

    private final DSLContext dsl;

    public JooqIdentityQueryRepository(DSLContext dsl) {
        this.dsl = Objects.requireNonNull(dsl, "dsl");
    }

    @Override
    public Optional<IdentityModels.CurrentUser> findCurrentUser(long tenantId, long membershipId) {
        var rows = dsl.select(
                IAM_USER.ID,
                IAM_AUTHENTICATION_CREDENTIAL.USERNAME,
                IAM_USER.DISPLAY_NAME,
                IAM_USER.STATUS,
                IAM_ROLE.ROLE_CODE)
            .from(IAM_MEMBERSHIP)
            .join(IAM_USER)
                .on(IAM_USER.ID.eq(IAM_MEMBERSHIP.USER_ID)
                    .and(IAM_USER.STATUS.eq(ACTIVE)))
            .join(IAM_AUTHENTICATION_CREDENTIAL)
                .on(IAM_AUTHENTICATION_CREDENTIAL.USER_ID.eq(IAM_USER.ID)
                    .and(IAM_AUTHENTICATION_CREDENTIAL.STATUS.eq(ACTIVE)))
            .leftJoin(IAM_MEMBERSHIP_ROLE)
                .on(IAM_MEMBERSHIP_ROLE.TENANT_ID.eq(IAM_MEMBERSHIP.TENANT_ID)
                    .and(IAM_MEMBERSHIP_ROLE.MEMBERSHIP_ID.eq(IAM_MEMBERSHIP.ID)))
            .leftJoin(IAM_ROLE)
                .on(IAM_ROLE.TENANT_ID.eq(IAM_MEMBERSHIP.TENANT_ID)
                    .and(IAM_ROLE.ID.eq(IAM_MEMBERSHIP_ROLE.ROLE_ID))
                    .and(IAM_ROLE.STATUS.eq(ACTIVE)))
            .where(IAM_MEMBERSHIP.TENANT_ID.eq(tenantId)
                .and(IAM_MEMBERSHIP.ID.eq(membershipId))
                .and(IAM_MEMBERSHIP.STATUS.eq(ACTIVE)))
            .orderBy(IAM_ROLE.ROLE_CODE.nullsLast())
            .fetch();

        if (rows.isEmpty()) {
            return Optional.empty();
        }
        var first = rows.getFirst();
        List<String> roles = rows.stream()
            .map(row -> row.get(IAM_ROLE.ROLE_CODE))
            .filter(Objects::nonNull)
            .distinct()
            .toList();
        return Optional.of(new IdentityModels.CurrentUser(
            first.get(IAM_USER.ID),
            first.get(IAM_AUTHENTICATION_CREDENTIAL.USERNAME),
            first.get(IAM_USER.DISPLAY_NAME),
            "",
            roles,
            "/dashboard"));
    }

    @Override
    public List<String> findPermissionCodes(long tenantId, long membershipId) {
        return dsl.selectDistinct(IAM_PERMISSION.PERMISSION_CODE)
            .from(IAM_MEMBERSHIP_ROLE)
            .join(IAM_ROLE)
                .on(IAM_ROLE.TENANT_ID.eq(IAM_MEMBERSHIP_ROLE.TENANT_ID)
                    .and(IAM_ROLE.ID.eq(IAM_MEMBERSHIP_ROLE.ROLE_ID))
                    .and(IAM_ROLE.STATUS.eq(ACTIVE)))
            .join(IAM_ROLE_GRANT)
                .on(IAM_ROLE_GRANT.TENANT_ID.eq(IAM_ROLE.TENANT_ID)
                    .and(IAM_ROLE_GRANT.ROLE_ID.eq(IAM_ROLE.ID))
                    .and(IAM_ROLE_GRANT.STATUS.eq(ACTIVE)))
            .join(IAM_PERMISSION)
                .on(IAM_PERMISSION.ID.eq(IAM_ROLE_GRANT.PERMISSION_ID)
                    .and(IAM_PERMISSION.STATUS.eq(ACTIVE)))
            .join(IAM_GRANT_DIMENSION)
                .on(IAM_GRANT_DIMENSION.GRANT_ID.eq(IAM_ROLE_GRANT.ID)
                    .and(IAM_GRANT_DIMENSION.DIMENSION_CODE.eq("TENANT"))
                    .and(IAM_GRANT_DIMENSION.SCOPE_MODE.eq("TENANT_ALL")))
            .where(IAM_MEMBERSHIP_ROLE.TENANT_ID.eq(tenantId)
                .and(IAM_MEMBERSHIP_ROLE.MEMBERSHIP_ID.eq(membershipId))
                .and(IAM_PERMISSION.REQUIRED_DIMENSIONS.eq(new String[]{"TENANT"}))
                .and(IAM_ROLE_GRANT.VALID_FROM.isNull()
                    .or(IAM_ROLE_GRANT.VALID_FROM.le(DSL.currentOffsetDateTime())))
                .and(IAM_ROLE_GRANT.VALID_UNTIL.isNull()
                    .or(IAM_ROLE_GRANT.VALID_UNTIL.gt(DSL.currentOffsetDateTime()))))
            .orderBy(IAM_PERMISSION.PERMISSION_CODE)
            .fetch(IAM_PERMISSION.PERMISSION_CODE);
    }

    @Override
    public List<IdentityModels.Menu> findAccessibleMenus(long tenantId, long membershipId) {
        List<Long> directlyAssignedIds = dsl.selectDistinct(IAM_MENU.ID)
            .from(IAM_ROLE_MENU)
            .join(IAM_MEMBERSHIP_ROLE)
                .on(IAM_MEMBERSHIP_ROLE.TENANT_ID.eq(IAM_ROLE_MENU.TENANT_ID)
                    .and(IAM_MEMBERSHIP_ROLE.ROLE_ID.eq(IAM_ROLE_MENU.ROLE_ID)))
            .join(IAM_ROLE)
                .on(IAM_ROLE.TENANT_ID.eq(IAM_ROLE_MENU.TENANT_ID)
                    .and(IAM_ROLE.ID.eq(IAM_ROLE_MENU.ROLE_ID))
                    .and(IAM_ROLE.STATUS.eq(ACTIVE)))
            .join(IAM_MENU)
                .on(IAM_MENU.TENANT_ID.eq(IAM_ROLE_MENU.TENANT_ID)
                    .and(IAM_MENU.ID.eq(IAM_ROLE_MENU.MENU_ID))
                    .and(IAM_MENU.STATUS.eq(ACTIVE))
                    .and(IAM_MENU.MENU_TYPE.in("DIRECTORY", "PAGE", "EMBEDDED", "LINK")))
            .where(IAM_ROLE_MENU.TENANT_ID.eq(tenantId)
                .and(IAM_MEMBERSHIP_ROLE.MEMBERSHIP_ID.eq(membershipId)))
            .limit(MAX_TREE_NODES + 1)
            .fetch(IAM_MENU.ID);
        requireTreeCapacity(directlyAssignedIds);
        if (directlyAssignedIds.isEmpty()) {
            return List.of();
        }

        List<IdentityModels.Menu> tenantRoutes = dsl.select(
                IAM_MENU.ID,
                IAM_MENU.PARENT_ID,
                IAM_MENU.MENU_TYPE,
                IAM_MENU.MENU_NAME,
                IAM_MENU.ROUTE_NAME,
                IAM_MENU.ROUTE_PATH,
                IAM_MENU.COMPONENT_PATH,
                IAM_MENU.REDIRECT_PATH,
                IAM_MENU.AUTH_CODE,
                IAM_MENU.META_JSON,
                IAM_MENU.STATUS,
                IAM_MENU.ROW_VERSION,
                IAM_MENU.SORT_ORDER)
            .from(IAM_MENU)
            .where(IAM_MENU.TENANT_ID.eq(tenantId)
                .and(IAM_MENU.STATUS.eq(ACTIVE))
                .and(IAM_MENU.MENU_TYPE.in("DIRECTORY", "PAGE", "EMBEDDED", "LINK")))
            .orderBy(IAM_MENU.SORT_ORDER, IAM_MENU.ID)
            .limit(MAX_TREE_NODES + 1)
            .fetch(this::menu);
        requireTreeCapacity(tenantRoutes);

        Map<Long, IdentityModels.Menu> routesById = new LinkedHashMap<>();
        tenantRoutes.forEach(route -> routesById.put(route.id(), route));
        Set<Long> includedIds = new LinkedHashSet<>();
        for (Long assignedId : directlyAssignedIds) {
            Set<Long> branch = new LinkedHashSet<>();
            Long currentId = assignedId;
            boolean complete = true;
            while (currentId != null) {
                if (!branch.add(currentId)) {
                    complete = false;
                    break;
                }
                IdentityModels.Menu current = routesById.get(currentId);
                if (current == null) {
                    complete = false;
                    break;
                }
                currentId = current.parentId();
            }
            if (complete) {
                includedIds.addAll(branch);
            }
        }
        return tenantRoutes.stream().filter(route -> includedIds.contains(route.id())).toList();
    }

    private static void requireTreeCapacity(List<?> rows) {
        if (rows.size() > MAX_TREE_NODES) {
            throw new IdentityAdministrationService.TreeLimitExceededException("Tree node limit exceeded");
        }
    }

    public IdentityModels.Page<IdentityModels.User> findUsers(long tenantId, IdentityModels.UserQuery query) {
        Condition condition = userCondition(tenantId, query);
        var baseRows = dsl.select(
                IAM_USER.ID,
                IAM_MEMBERSHIP.ID,
                IAM_AUTHENTICATION_CREDENTIAL.USERNAME,
                IAM_USER.DISPLAY_NAME,
                IAM_USER.STATUS,
                IAM_MEMBERSHIP.DEPARTMENT_ID,
                IAM_DEPARTMENT.DEPARTMENT_NAME,
                IAM_MEMBERSHIP.STATUS,
                IAM_MEMBERSHIP.ROW_VERSION,
                IAM_USER.REMARK,
                IAM_MEMBERSHIP.CREATED_AT)
            .from(IAM_MEMBERSHIP)
            .join(IAM_USER).on(IAM_USER.ID.eq(IAM_MEMBERSHIP.USER_ID))
            .join(IAM_AUTHENTICATION_CREDENTIAL)
                .on(IAM_AUTHENTICATION_CREDENTIAL.USER_ID.eq(IAM_USER.ID))
            .leftJoin(IAM_DEPARTMENT)
                .on(IAM_DEPARTMENT.TENANT_ID.eq(IAM_MEMBERSHIP.TENANT_ID)
                    .and(IAM_DEPARTMENT.ID.eq(IAM_MEMBERSHIP.DEPARTMENT_ID)))
            .where(condition)
            .orderBy(IAM_MEMBERSHIP.CREATED_AT.desc(), IAM_MEMBERSHIP.ID.desc())
            .limit(query.pageSize())
            .offset((query.page() - 1) * query.pageSize())
            .fetch();

        List<Long> membershipIds = baseRows.getValues(IAM_MEMBERSHIP.ID);
        Map<Long, List<RoleView>> rolesByMembership = rolesByMembership(tenantId, membershipIds);
        List<IdentityModels.User> users = baseRows.stream()
            .map(row -> user(row, rolesByMembership.getOrDefault(row.get(IAM_MEMBERSHIP.ID), List.of())))
            .toList();
        long total = dsl.selectCount()
            .from(IAM_MEMBERSHIP)
            .join(IAM_USER).on(IAM_USER.ID.eq(IAM_MEMBERSHIP.USER_ID))
            .join(IAM_AUTHENTICATION_CREDENTIAL)
                .on(IAM_AUTHENTICATION_CREDENTIAL.USER_ID.eq(IAM_USER.ID))
            .where(condition)
            .fetchOne(0, long.class);
        return new IdentityModels.Page<>(users, total);
    }

    public IdentityModels.Page<IdentityModels.Role> findRoles(long tenantId, IdentityModels.RoleQuery query) {
        Condition condition = roleCondition(tenantId, query);
        var roleRows = dsl.select(
                IAM_ROLE.ID,
                IAM_ROLE.ROLE_NAME,
                IAM_ROLE.STATUS,
                IAM_ROLE.REMARK,
                IAM_ROLE.ROW_VERSION,
                IAM_ROLE.SYSTEM_ROLE,
                IAM_ROLE.ASSIGNABLE,
                IAM_ROLE.CREATED_AT)
            .from(IAM_ROLE)
            .where(condition)
            .orderBy(IAM_ROLE.CREATED_AT.desc(), IAM_ROLE.ID.desc())
            .limit(query.pageSize())
            .offset((query.page() - 1) * query.pageSize())
            .fetch();
        List<Long> roleIds = roleRows.getValues(IAM_ROLE.ID);
        Map<Long, List<Long>> menuIdsByRole = menuIdsByRole(tenantId, roleIds);
        List<IdentityModels.Role> roles = roleRows.stream()
            .map(row -> new IdentityModels.Role(
                row.get(IAM_ROLE.ID),
                row.get(IAM_ROLE.ROLE_NAME),
                menuIdsByRole.getOrDefault(row.get(IAM_ROLE.ID), List.of()),
                apiStatus(row.get(IAM_ROLE.STATUS)),
                row.get(IAM_ROLE.REMARK),
                row.get(IAM_ROLE.ROW_VERSION),
                Boolean.TRUE.equals(row.get(IAM_ROLE.SYSTEM_ROLE)),
                Boolean.TRUE.equals(row.get(IAM_ROLE.ASSIGNABLE)),
                instant(row.get(IAM_ROLE.CREATED_AT))))
            .toList();
        long total = dsl.selectCount()
            .from(IAM_ROLE)
            .where(condition)
            .fetchOne(0, long.class);
        return new IdentityModels.Page<>(roles, total);
    }

    public List<IdentityModels.Department> findDepartments(long tenantId) {
        return dsl.select(
                IAM_DEPARTMENT.ID,
                IAM_DEPARTMENT.PARENT_ID,
                IAM_DEPARTMENT.DEPARTMENT_NAME,
                IAM_DEPARTMENT.STATUS,
                IAM_DEPARTMENT.REMARK,
                IAM_DEPARTMENT.ROW_VERSION,
                IAM_DEPARTMENT.CREATED_AT)
            .from(IAM_DEPARTMENT)
            .where(IAM_DEPARTMENT.TENANT_ID.eq(tenantId))
            .orderBy(IAM_DEPARTMENT.CREATED_AT, IAM_DEPARTMENT.ID)
            .limit(MAX_TREE_NODES + 1)
            .fetch(row -> new IdentityModels.Department(
                row.get(IAM_DEPARTMENT.ID),
                row.get(IAM_DEPARTMENT.PARENT_ID),
                row.get(IAM_DEPARTMENT.DEPARTMENT_NAME),
                apiStatus(row.get(IAM_DEPARTMENT.STATUS)),
                row.get(IAM_DEPARTMENT.REMARK),
                row.get(IAM_DEPARTMENT.ROW_VERSION),
                instant(row.get(IAM_DEPARTMENT.CREATED_AT))));
    }

    public List<IdentityModels.Menu> findMenus(long tenantId) {
        return dsl.select(
                IAM_MENU.ID,
                IAM_MENU.PARENT_ID,
                IAM_MENU.MENU_TYPE,
                IAM_MENU.MENU_NAME,
                IAM_MENU.ROUTE_NAME,
                IAM_MENU.ROUTE_PATH,
                IAM_MENU.COMPONENT_PATH,
                IAM_MENU.REDIRECT_PATH,
                IAM_MENU.AUTH_CODE,
                IAM_MENU.META_JSON,
                IAM_MENU.STATUS,
                IAM_MENU.ROW_VERSION,
                IAM_MENU.SORT_ORDER)
            .from(IAM_MENU)
            .where(IAM_MENU.TENANT_ID.eq(tenantId))
            .orderBy(IAM_MENU.SORT_ORDER, IAM_MENU.ID)
            .limit(MAX_TREE_NODES + 1)
            .fetch(this::menu);
    }

    private Condition userCondition(long tenantId, IdentityModels.UserQuery query) {
        Condition condition = IAM_MEMBERSHIP.TENANT_ID.eq(tenantId)
            .and(IAM_MEMBERSHIP.STATUS.ne(TERMINATED));
        if (hasText(query.username())) {
            condition = condition.and(IAM_AUTHENTICATION_CREDENTIAL.USERNAME.containsIgnoreCase(query.username()));
        }
        if (hasText(query.name())) {
            condition = condition.and(IAM_USER.DISPLAY_NAME.containsIgnoreCase(query.name()));
        }
        if (query.id() != null) {
            condition = condition.and(IAM_USER.ID.eq(query.id()));
        }
        if (query.status() != null) {
            condition = condition.and(IAM_MEMBERSHIP.STATUS.eq(status(query.status())));
        }
        if (query.departmentId() != null) {
            condition = condition.and(IAM_MEMBERSHIP.DEPARTMENT_ID.eq(query.departmentId()));
        }
        if (query.startTime() != null) {
            condition = condition.and(IAM_MEMBERSHIP.CREATED_AT.ge(offsetDateTime(query.startTime())));
        }
        if (query.endTime() != null) {
            condition = condition.and(IAM_MEMBERSHIP.CREATED_AT.le(offsetDateTime(query.endTime())));
        }
        return condition;
    }

    private Condition roleCondition(long tenantId, IdentityModels.RoleQuery query) {
        Condition condition = IAM_ROLE.TENANT_ID.eq(tenantId);
        if (hasText(query.name())) {
            condition = condition.and(IAM_ROLE.ROLE_NAME.containsIgnoreCase(query.name()));
        }
        if (query.id() != null) {
            condition = condition.and(IAM_ROLE.ID.eq(query.id()));
        }
        if (query.status() != null) {
            condition = condition.and(IAM_ROLE.STATUS.eq(status(query.status())));
        }
        if (hasText(query.remark())) {
            condition = condition.and(DSL.coalesce(IAM_ROLE.REMARK, "").containsIgnoreCase(query.remark()));
        }
        if (query.startTime() != null) {
            condition = condition.and(IAM_ROLE.CREATED_AT.ge(offsetDateTime(query.startTime())));
        }
        if (query.endTime() != null) {
            condition = condition.and(IAM_ROLE.CREATED_AT.le(offsetDateTime(query.endTime())));
        }
        return condition;
    }

    private Map<Long, List<RoleView>> rolesByMembership(long tenantId, List<Long> membershipIds) {
        if (membershipIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<RoleView>> result = new LinkedHashMap<>();
        dsl.select(IAM_MEMBERSHIP_ROLE.MEMBERSHIP_ID, IAM_ROLE.ID, IAM_ROLE.ROLE_NAME)
            .from(IAM_MEMBERSHIP_ROLE)
            .join(IAM_ROLE)
                .on(IAM_ROLE.TENANT_ID.eq(IAM_MEMBERSHIP_ROLE.TENANT_ID)
                    .and(IAM_ROLE.ID.eq(IAM_MEMBERSHIP_ROLE.ROLE_ID)))
            .where(IAM_MEMBERSHIP_ROLE.TENANT_ID.eq(tenantId)
                .and(IAM_MEMBERSHIP_ROLE.MEMBERSHIP_ID.in(membershipIds)))
            .orderBy(IAM_MEMBERSHIP_ROLE.MEMBERSHIP_ID, IAM_ROLE.ID)
            .forEach(row -> result.computeIfAbsent(row.get(IAM_MEMBERSHIP_ROLE.MEMBERSHIP_ID), ignored -> new ArrayList<>())
                .add(new RoleView(row.get(IAM_ROLE.ID), row.get(IAM_ROLE.ROLE_NAME))));
        return result;
    }

    private Map<Long, List<Long>> menuIdsByRole(long tenantId, List<Long> roleIds) {
        if (roleIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<Long>> result = new LinkedHashMap<>();
        dsl.select(IAM_ROLE_MENU.ROLE_ID, IAM_ROLE_MENU.MENU_ID)
            .from(IAM_ROLE_MENU)
            .join(IAM_MENU)
                .on(IAM_MENU.TENANT_ID.eq(IAM_ROLE_MENU.TENANT_ID)
                    .and(IAM_MENU.ID.eq(IAM_ROLE_MENU.MENU_ID)))
            .where(IAM_ROLE_MENU.TENANT_ID.eq(tenantId)
                .and(IAM_ROLE_MENU.ROLE_ID.in(roleIds))
                .and(IAM_MENU.STATUS.eq(ACTIVE))
                .and(IAM_MENU.MENU_TYPE.in("DIRECTORY", "PAGE", "EMBEDDED", "LINK")))
            .orderBy(IAM_ROLE_MENU.ROLE_ID, IAM_ROLE_MENU.MENU_ID)
            .forEach(row -> result.computeIfAbsent(row.get(IAM_ROLE_MENU.ROLE_ID), ignored -> new ArrayList<>())
                .add(row.get(IAM_ROLE_MENU.MENU_ID)));
        return result;
    }

    private IdentityModels.User user(Record row, List<RoleView> roles) {
        return new IdentityModels.User(
            row.get(IAM_USER.ID),
            row.get(IAM_MEMBERSHIP.ID),
            row.get(IAM_AUTHENTICATION_CREDENTIAL.USERNAME),
            row.get(IAM_USER.DISPLAY_NAME),
            row.get(IAM_MEMBERSHIP.DEPARTMENT_ID),
            row.get(IAM_DEPARTMENT.DEPARTMENT_NAME),
            roles.stream().map(RoleView::id).toList(),
            roles.stream().map(RoleView::name).toList(),
            apiStatus(row.get(IAM_MEMBERSHIP.STATUS)),
            row.get(IAM_USER.STATUS),
            row.get(IAM_MEMBERSHIP.ROW_VERSION),
            row.get(IAM_USER.REMARK),
            instant(row.get(IAM_MEMBERSHIP.CREATED_AT)));
    }

    private IdentityModels.Menu menu(Record row) {
        String routeName = row.get(IAM_MENU.ROUTE_NAME);
        return new IdentityModels.Menu(
            row.get(IAM_MENU.ID),
            row.get(IAM_MENU.PARENT_ID),
            menuTypeForApi(row.get(IAM_MENU.MENU_TYPE)),
            hasText(routeName) ? routeName : row.get(IAM_MENU.MENU_NAME),
            row.get(IAM_MENU.ROUTE_PATH),
            row.get(IAM_MENU.COMPONENT_PATH),
            row.get(IAM_MENU.REDIRECT_PATH),
            row.get(IAM_MENU.AUTH_CODE),
            json(row.get(IAM_MENU.META_JSON)),
            apiStatus(row.get(IAM_MENU.STATUS)),
            row.get(IAM_MENU.ROW_VERSION));
    }

    private static String menuTypeForApi(String value) {
        return switch (value) {
            case "DIRECTORY" -> "catalog";
            case "PAGE" -> "menu";
            case "EMBEDDED" -> "embedded";
            case "LINK" -> "link";
            case "BUTTON" -> "button";
            default -> throw new IllegalStateException("Unknown menu type");
        };
    }

    private static int apiStatus(String value) {
        return ACTIVE.equals(value) ? 1 : 0;
    }

    private static String status(int value) {
        return value == 1 ? ACTIVE : "DISABLED";
    }

    private static OffsetDateTime offsetDateTime(Instant value) {
        return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private static Instant instant(OffsetDateTime value) {
        return value.toInstant();
    }

    private static String json(JSONB value) {
        return value == null ? "{}" : value.data();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record RoleView(long id, String name) {
    }
}
