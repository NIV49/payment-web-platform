package com.niv.payment.permission.persistence.repository;

import com.niv.payment.permission.domain.AdministrationActor;
import com.niv.payment.permission.port.MenuAdministrationPort;
import com.niv.payment.permission.service.IdentityAdministrationService;
import com.niv.payment.permission.service.IdentityModels;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.impl.DSL;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_MENU;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_PERMISSION;
import static com.niv.payment.permission.persistence.repository.JooqAdministrationSupport.ACTIVE;
import static com.niv.payment.permission.persistence.repository.JooqAdministrationSupport.DISABLED;
import static com.niv.payment.permission.persistence.repository.JooqAdministrationSupport.notFound;
import static com.niv.payment.permission.persistence.repository.JooqAdministrationSupport.status;
import static com.niv.payment.permission.service.IdentityAdministrationService.MAX_TREE_DEPTH;
import static com.niv.payment.permission.service.IdentityAdministrationService.MAX_TREE_NODES;

/** Vben menu-contract persistence adapter. */
public class JooqMenuAdministrationRepository implements MenuAdministrationPort {
    private static final Set<String> NON_BINDABLE_COMPATIBILITY_CODES = Set.of(
        "menu:manage", "department:manage");
    private final DSLContext dsl;
    private final JooqIdentityQueryRepository queries;
    private final JooqAdministrationSupport support;

    public JooqMenuAdministrationRepository(DSLContext dsl,
                                            JooqIdentityQueryRepository queries,
                                            Supplier<String> traceIdSupplier) {
        this.dsl = Objects.requireNonNull(dsl, "dsl");
        this.queries = Objects.requireNonNull(queries, "queries");
        this.support = new JooqAdministrationSupport(dsl, traceIdSupplier);
    }

    @Override
    public List<IdentityModels.Menu> findMenus(long tenantId) {
        return queries.findMenus(tenantId);
    }

    @Override
    public List<IdentityModels.Menu> findMenus(long tenantId, boolean selectableOnly) {
        return queries.findMenus(tenantId, selectableOnly);
    }

    @Override
    @Transactional
    public long createMenu(long tenantId, AdministrationActor actor, IdentityModels.MenuCommand command) {
        support.requirePlatformTenant(tenantId);
        support.lockTenant(tenantId, actor);
        Map<Long, TreeNode> nodes = nodes(tenantId);
        if (nodes.size() >= MAX_TREE_NODES) {
            throw new IdentityAdministrationService.TreeLimitExceededException("Tree node limit exceeded");
        }
        long menuId = support.nextId();
        String state = status(command.status());
        String routeName = command.name().trim();
        String routePath = JooqAdministrationSupport.blankToNull(command.path());
        if (!parentAllowed(nodes, menuId, command.parentId(), state)) {
            throw new IllegalArgumentException("Invalid menu parent");
        }
        String persistedType = menuType(command.type());
        validateAuthorizationCode(persistedType, command.authCode());
        requireUniqueRoute(tenantId, routeName, routePath, null);
        dsl.insertInto(IAM_MENU)
            .set(IAM_MENU.ID, menuId)
            .set(IAM_MENU.TENANT_ID, tenantId)
            .set(IAM_MENU.PARENT_ID, command.parentId())
            .set(IAM_MENU.MENU_TYPE, persistedType)
            .set(IAM_MENU.MENU_NAME, routeName)
            .set(IAM_MENU.ROUTE_NAME, routeName)
            .set(IAM_MENU.ROUTE_PATH, routePath)
            .set(IAM_MENU.COMPONENT_PATH, JooqAdministrationSupport.blankToNull(command.component()))
            .set(IAM_MENU.REDIRECT_PATH, JooqAdministrationSupport.blankToNull(command.redirect()))
            .set(IAM_MENU.AUTH_CODE, JooqAdministrationSupport.blankToNull(command.authCode()))
            .set(IAM_MENU.META_JSON, json(command.metaJson()))
            .set(IAM_MENU.STATUS, state)
            .set(IAM_MENU.SORT_ORDER, 999)
            .execute();
        support.audit(tenantId, actor.membershipId(), "MENU", menuId, "CREATE", "menu:create");
        return menuId;
    }

    @Override
    @Transactional
    public void updateMenu(long tenantId, AdministrationActor actor, long menuId,
                           IdentityModels.MenuCommand command, long expectedVersion) {
        support.requirePlatformTenant(tenantId);
        support.lockTenant(tenantId, actor);
        Map<Long, TreeNode> nodes = nodes(tenantId);
        if (!nodes.containsKey(menuId)) {
            throw notFound("Menu");
        }
        requireUserManaged(nodes.get(menuId));
        String state = status(command.status());
        String routeName = command.name().trim();
        String routePath = JooqAdministrationSupport.blankToNull(command.path());
        if (!parentAllowed(nodes, menuId, command.parentId(), state)) {
            throw new IllegalArgumentException("Invalid menu parent");
        }
        String persistedType = menuType(command.type());
        if ("BUTTON".equals(persistedType) && nodes.values().stream()
            .anyMatch(node -> Objects.equals(node.parentId(), menuId))) {
            throw new IllegalArgumentException("A button cannot be a menu parent");
        }
        validateAuthorizationCode(persistedType, command.authCode());
        if (DISABLED.equals(state) && hasActiveDescendants(tenantId, menuId)) {
            throw new IdentityAdministrationService.DataConflictException(
                "Menu has active descendants");
        }
        requireUniqueRoute(tenantId, routeName, routePath, menuId);
        int updated = dsl.update(IAM_MENU)
            .set(IAM_MENU.PARENT_ID, command.parentId())
            .set(IAM_MENU.MENU_TYPE, persistedType)
            .set(IAM_MENU.MENU_NAME, routeName)
            .set(IAM_MENU.ROUTE_NAME, routeName)
            .set(IAM_MENU.ROUTE_PATH, routePath)
            .set(IAM_MENU.COMPONENT_PATH, JooqAdministrationSupport.blankToNull(command.component()))
            .set(IAM_MENU.REDIRECT_PATH, JooqAdministrationSupport.blankToNull(command.redirect()))
            .set(IAM_MENU.AUTH_CODE, JooqAdministrationSupport.blankToNull(command.authCode()))
            .set(IAM_MENU.META_JSON, json(command.metaJson()))
            .set(IAM_MENU.STATUS, state)
            .set(IAM_MENU.UPDATED_AT, DSL.currentOffsetDateTime())
            .set(IAM_MENU.ROW_VERSION, IAM_MENU.ROW_VERSION.plus(1L))
            .where(IAM_MENU.TENANT_ID.eq(tenantId)
                .and(IAM_MENU.ID.eq(menuId))
                .and(IAM_MENU.ROW_VERSION.eq(expectedVersion))
                .and(IAM_MENU.DELETED_AT.isNull()))
            .execute();
        requireSuccessfulMutation(updated, tenantId, menuId);
        support.audit(tenantId, actor.membershipId(), "MENU", menuId, "UPDATE", "menu:update");
    }

    @Override
    @Transactional
    public void deleteMenu(long tenantId, AdministrationActor actor, long menuId, long expectedVersion) {
        support.requirePlatformTenant(tenantId);
        support.lockTenant(tenantId, actor);
        TreeNode target = nodes(tenantId).get(menuId);
        if (target == null) {
            throw notFound("Menu");
        }
        requireUserManaged(target);
        if (hasLiveDescendants(tenantId, menuId)) {
            throw new IdentityAdministrationService.DataConflictException(
                "Menu has live descendants");
        }
        int updated = dsl.update(IAM_MENU)
            .set(IAM_MENU.STATUS, DISABLED)
            .set(IAM_MENU.DELETED_AT, DSL.currentOffsetDateTime())
            .set(IAM_MENU.UPDATED_AT, DSL.currentOffsetDateTime())
            .set(IAM_MENU.ROW_VERSION, IAM_MENU.ROW_VERSION.plus(1L))
            .where(IAM_MENU.TENANT_ID.eq(tenantId)
                .and(IAM_MENU.ID.eq(menuId))
                .and(IAM_MENU.ROW_VERSION.eq(expectedVersion))
                .and(IAM_MENU.DELETED_AT.isNull()))
            .execute();
        requireSuccessfulMutation(updated, tenantId, menuId);
        support.audit(tenantId, actor.membershipId(), "MENU", menuId, "DELETE", "menu:delete");
    }

    @Override
    public boolean menuNameExists(long tenantId, String name, Long excludedId) {
        Field<String> effectiveRouteName = DSL.field(
            "COALESCE(NULLIF(BTRIM({0}), ''), {1})",
            String.class,
            IAM_MENU.ROUTE_NAME,
            IAM_MENU.MENU_NAME);
        var condition = IAM_MENU.TENANT_ID.eq(tenantId)
            .and(IAM_MENU.DELETED_AT.isNull())
            .and(effectiveRouteName.equalIgnoreCase(name.trim()));
        if (excludedId != null) {
            condition = condition.and(IAM_MENU.ID.ne(excludedId));
        }
        return dsl.fetchExists(dsl.selectOne().from(IAM_MENU).where(condition));
    }

    @Override
    public boolean menuPathExists(long tenantId, String path, Long excludedId) {
        var condition = IAM_MENU.TENANT_ID.eq(tenantId)
            .and(IAM_MENU.DELETED_AT.isNull())
            .and(normalizedRoutePath(IAM_MENU.ROUTE_PATH)
                .equalIgnoreCase(stripTrailingSlashes(path.trim())));
        if (excludedId != null) {
            condition = condition.and(IAM_MENU.ID.ne(excludedId));
        }
        return dsl.fetchExists(dsl.selectOne().from(IAM_MENU).where(condition));
    }

    private boolean parentAllowed(Map<Long, TreeNode> nodes, long menuId, Long parentId, String state) {
        TreeNode parent = parentId == null ? null : nodes.get(parentId);
        if (parentId != null && (parent == null || "BUTTON".equals(parent.type())
            || (ACTIVE.equals(state) && !ACTIVE.equals(parent.status())))) {
            return false;
        }
        Map<Long, TreeNode> candidate = new LinkedHashMap<>(nodes);
        candidate.put(menuId, new TreeNode(parentId, state,
            nodes.containsKey(menuId) ? nodes.get(menuId).type() : null,
            nodes.containsKey(menuId) && nodes.get(menuId).systemManaged()));
        return isValidTree(candidate);
    }

    private void requireSuccessfulMutation(int updated, long tenantId, long menuId) {
        if (updated == 1) {
            return;
        }
        Long currentVersion = dsl.select(IAM_MENU.ROW_VERSION)
            .from(IAM_MENU)
            .where(IAM_MENU.TENANT_ID.eq(tenantId)
                .and(IAM_MENU.ID.eq(menuId))
                .and(IAM_MENU.DELETED_AT.isNull()))
            .fetchOne(IAM_MENU.ROW_VERSION);
        if (currentVersion == null) {
            throw notFound("Menu");
        }
        throw new IdentityAdministrationService.OptimisticLockException();
    }

    private void requireUniqueRoute(long tenantId, String routeName, String routePath, Long excludedId) {
        if (menuNameExists(tenantId, routeName, excludedId)
            || (routePath != null && menuPathExists(tenantId, routePath, excludedId))) {
            throw new IdentityAdministrationService.DataConflictException(
                "Menu route name or path already exists");
        }
    }

    private Map<Long, TreeNode> nodes(long tenantId) {
        Map<Long, TreeNode> nodes = new LinkedHashMap<>();
        var rows = dsl.select(IAM_MENU.ID, IAM_MENU.PARENT_ID, IAM_MENU.STATUS,
                IAM_MENU.MENU_TYPE, IAM_MENU.SYSTEM_MANAGED)
            .from(IAM_MENU)
            .where(IAM_MENU.TENANT_ID.eq(tenantId).and(IAM_MENU.DELETED_AT.isNull()))
            .limit(MAX_TREE_NODES + 1)
            .fetch();
        if (rows.size() > MAX_TREE_NODES) {
            throw new IdentityAdministrationService.TreeLimitExceededException("Tree node limit exceeded");
        }
        rows.forEach(row -> nodes.put(row.get(IAM_MENU.ID),
                new TreeNode(row.get(IAM_MENU.PARENT_ID), row.get(IAM_MENU.STATUS),
                    row.get(IAM_MENU.MENU_TYPE), Boolean.TRUE.equals(row.get(IAM_MENU.SYSTEM_MANAGED)))));
        return nodes;
    }

    private void validateAuthorizationCode(String persistedType, String authCode) {
        String code = JooqAdministrationSupport.blankToNull(authCode);
        if ("BUTTON".equals(persistedType) && code == null) {
            throw new IllegalArgumentException("Button permission code is required");
        }
        if (code != null && NON_BINDABLE_COMPATIBILITY_CODES.contains(code)) {
            throw new IllegalArgumentException("Compatibility permission code cannot be bound to a menu");
        }
        if (code != null && !dsl.fetchExists(dsl.selectOne().from(IAM_PERMISSION)
            .where(IAM_PERMISSION.PERMISSION_CODE.eq(code).and(IAM_PERMISSION.STATUS.eq(ACTIVE))))) {
            throw new IllegalArgumentException("Button permission code is unknown or disabled");
        }
    }

    private static boolean isValidTree(Map<Long, TreeNode> nodes) {
        for (Long startId : nodes.keySet()) {
            Set<Long> visited = new HashSet<>();
            Long current = startId;
            int depth = 0;
            while (current != null) {
                TreeNode node = nodes.get(current);
                if (node == null || !visited.add(current) || ++depth > MAX_TREE_DEPTH) {
                    return false;
                }
                current = node.parentId();
            }
        }
        return true;
    }

    private boolean hasActiveDescendants(long tenantId, long menuId) {
        Map<Long, TreeNode> nodes = nodes(tenantId);
        Set<Long> subtree = new HashSet<>();
        subtree.add(menuId);
        boolean changed;
        do {
            changed = false;
            for (Map.Entry<Long, TreeNode> entry : nodes.entrySet()) {
                if (entry.getValue().parentId() != null
                    && subtree.contains(entry.getValue().parentId())
                    && subtree.add(entry.getKey())) {
                    changed = true;
                }
            }
        } while (changed);
        return subtree.stream()
            .filter(id -> id != menuId)
            .map(nodes::get)
            .filter(Objects::nonNull)
            .anyMatch(node -> ACTIVE.equals(node.status()));
    }

    private boolean hasLiveDescendants(long tenantId, long menuId) {
        Map<Long, TreeNode> nodes = nodes(tenantId);
        Set<Long> subtree = new HashSet<>();
        subtree.add(menuId);
        boolean changed;
        do {
            changed = false;
            for (Map.Entry<Long, TreeNode> entry : nodes.entrySet()) {
                if (entry.getValue().parentId() != null
                    && subtree.contains(entry.getValue().parentId())
                    && subtree.add(entry.getKey())) {
                    changed = true;
                }
            }
        } while (changed);
        return subtree.size() > 1;
    }

    private static JSONB json(String value) {
        return JSONB.valueOf(value == null || value.isBlank() ? "{}" : value);
    }

    private static Field<String> normalizedRoutePath(Field<String> path) {
        return DSL.field(
            "CASE WHEN {0} = '/' THEN '/' ELSE regexp_replace({0}, '/+$', '') END",
            String.class,
            path);
    }

    private static String stripTrailingSlashes(String path) {
        int end = path.length();
        while (end > 1 && path.charAt(end - 1) == '/') {
            end--;
        }
        return path.substring(0, end);
    }

    private static String menuType(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "catalog" -> "DIRECTORY";
            case "menu" -> "PAGE";
            case "embedded" -> "EMBEDDED";
            case "link" -> "LINK";
            case "button" -> "BUTTON";
            default -> throw new IllegalArgumentException("Invalid menu type");
        };
    }

    private static void requireUserManaged(TreeNode node) {
        if (node.systemManaged()) {
            throw new IdentityAdministrationService.DataConflictException(
                "System-managed menu cannot be modified");
        }
    }

    private record TreeNode(Long parentId, String status, String type, boolean systemManaged) {
    }
}
