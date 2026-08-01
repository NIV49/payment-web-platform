package com.niv.payment.permission.service;

import com.niv.payment.permission.domain.AdministrationActor;
import com.niv.payment.permission.port.DepartmentAdministrationPort;
import com.niv.payment.permission.port.IdentityQueryPort;
import com.niv.payment.permission.port.MenuAdministrationPort;
import com.niv.payment.permission.port.RoleAdministrationPort;
import com.niv.payment.permission.port.UserAdministrationPort;

import java.util.List;
import java.util.Objects;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** Tenant and operator identifiers come only from the trusted server-side session. */
public final class IdentityAdministrationService {
    public static final int MAX_TREE_NODES = 2_000;
    public static final int MAX_TREE_DEPTH = 32;
    private static final int MAX_ROLES_PER_MEMBERSHIP = 256;
    private static final int MAX_MENUS_PER_ROLE = 2_048;
    private final IdentityQueryPort queries;
    private final UserAdministrationPort users;
    private final RoleAdministrationPort roles;
    private final DepartmentAdministrationPort departments;
    private final MenuAdministrationPort menus;

    public IdentityAdministrationService(IdentityQueryPort queries, UserAdministrationPort users,
                                         RoleAdministrationPort roles, DepartmentAdministrationPort departments,
                                         MenuAdministrationPort menus) {
        this.queries = Objects.requireNonNull(queries, "queries");
        this.users = Objects.requireNonNull(users, "users");
        this.roles = Objects.requireNonNull(roles, "roles");
        this.departments = Objects.requireNonNull(departments, "departments");
        this.menus = Objects.requireNonNull(menus, "menus");
    }

    public IdentityModels.CurrentUser currentUser(long tenantId, long membershipId) {
        return queries.findCurrentUser(tenantId, membershipId)
            .orElseThrow(() -> new ResourceNotFoundException("User membership was not found"));
    }
    public List<String> permissionCodes(long tenantId, long membershipId) {
        return queries.findPermissionCodes(tenantId, membershipId);
    }
    public List<IdentityModels.Menu> accessibleMenus(long tenantId, long membershipId) {
        return boundedTree(queries.findAccessibleMenus(tenantId, membershipId),
            IdentityModels.Menu::id, IdentityModels.Menu::parentId);
    }
    public IdentityModels.Page<IdentityModels.User> users(long tenantId, IdentityModels.UserQuery query) {
        Objects.requireNonNull(query, "query"); if (query.status()!=null) validStatus(query.status());
        validRange(query.startTime(), query.endTime());
        int page = validPage(query.page());
        int pageSize = validPageSize(query.pageSize());
        validOffset(page, pageSize);
        return users.findUsers(tenantId, new IdentityModels.UserQuery(query.username(), query.name(), query.id(),
            query.status(), query.departmentId(), query.startTime(), query.endTime(), page, pageSize));
    }
    public IdentityModels.Page<IdentityModels.Role> roles(long tenantId, IdentityModels.RoleQuery query) {
        Objects.requireNonNull(query, "query"); if (query.status()!=null) validStatus(query.status());
        validRange(query.startTime(), query.endTime());
        int page = validPage(query.page());
        int pageSize = validPageSize(query.pageSize());
        validOffset(page, pageSize);
        return roles.findRoles(tenantId, new IdentityModels.RoleQuery(query.name(), query.id(), query.status(),
            query.remark(), query.startTime(), query.endTime(), page, pageSize));
    }
    public List<IdentityModels.Department> departments(long tenantId) {
        return boundedTree(departments.findDepartments(tenantId),
            IdentityModels.Department::id, IdentityModels.Department::parentId);
    }
    public List<IdentityModels.Department> departments(long tenantId, boolean selectableOnly) {
        return boundedTree(departments.findDepartments(tenantId, selectableOnly),
            IdentityModels.Department::id, IdentityModels.Department::parentId);
    }
    public List<IdentityModels.Menu> menus(long tenantId) {
        return boundedTree(menus.findMenus(tenantId), IdentityModels.Menu::id, IdentityModels.Menu::parentId);
    }
    public List<IdentityModels.Menu> menus(long tenantId, boolean selectableOnly) {
        return boundedTree(menus.findMenus(tenantId, selectableOnly),
            IdentityModels.Menu::id, IdentityModels.Menu::parentId);
    }

    public long createUser(long tenantId, AdministrationActor actor, IdentityModels.UserCreateCommand command) {
        validateUserCreate(command); return users.createUser(tenantId, validActor(actor), command);
    }
    public void updateUser(long tenantId, AdministrationActor actor, long id,
                           IdentityModels.MembershipUpdateCommand command) {
        validateMembershipUpdate(command); users.updateUser(tenantId, validActor(actor), positiveId(id), command);
    }
    public long updateUserStatus(long tenantId, AdministrationActor actor, long id, int status, long version) {
        validStatus(status);
        return users.updateUserStatus(tenantId, validActor(actor), positiveId(id), status, validVersion(version));
    }
    public IdentityModels.PasswordResetResult resetUserPassword(long tenantId, AdministrationActor actor,
                                                                long id, long credentialVersion) {
        return users.resetUserPassword(tenantId, validActor(actor), positiveId(id),
            validVersion(credentialVersion));
    }
    public void deleteUser(long tenantId, AdministrationActor actor, long id, long expectedVersion) {
        users.deleteUser(tenantId, validActor(actor), positiveId(id), validVersion(expectedVersion));
    }
    public long createRole(long tenantId, AdministrationActor actor, IdentityModels.RoleCommand command) {
        validateRole(command); return roles.createRole(tenantId, validActor(actor), command);
    }
    public void updateRole(long tenantId, AdministrationActor actor, long id,
                           IdentityModels.RoleCommand command, long expectedVersion) {
        validateRole(command);
        roles.updateRole(tenantId, validActor(actor), positiveId(id), command, validVersion(expectedVersion));
    }
    public void updateRoleStatus(long tenantId, AdministrationActor actor, long id,
                                 int status, long expectedVersion) {
        validStatus(status);
        roles.updateRoleStatus(tenantId, validActor(actor), positiveId(id), status, validVersion(expectedVersion));
    }
    public void deleteRole(long tenantId, AdministrationActor actor, long id, long expectedVersion) {
        roles.deleteRole(tenantId, validActor(actor), positiveId(id), validVersion(expectedVersion));
    }
    public long createDepartment(long tenantId, AdministrationActor actor,
                                 IdentityModels.DepartmentCommand command) {
        validateDepartment(command); return departments.createDepartment(tenantId, validActor(actor), command);
    }
    public void updateDepartment(long tenantId, AdministrationActor actor, long id,
                                 IdentityModels.DepartmentCommand command, long expectedVersion) {
        validateDepartment(command);
        departments.updateDepartment(tenantId, validActor(actor), positiveId(id), command,
            validVersion(expectedVersion));
    }
    public void deleteDepartment(long tenantId, AdministrationActor actor, long id, long expectedVersion) {
        departments.deleteDepartment(tenantId, validActor(actor), positiveId(id), validVersion(expectedVersion));
    }
    public long createMenu(long tenantId, AdministrationActor actor, IdentityModels.MenuCommand command) {
        validateMenu(command); return menus.createMenu(tenantId, validActor(actor), command);
    }
    public void updateMenu(long tenantId, AdministrationActor actor, long id,
                           IdentityModels.MenuCommand command, long expectedVersion) {
        validateMenu(command);
        menus.updateMenu(tenantId, validActor(actor), positiveId(id), command, validVersion(expectedVersion));
    }
    public void deleteMenu(long tenantId, AdministrationActor actor, long id, long expectedVersion) {
        menus.deleteMenu(tenantId, validActor(actor), positiveId(id), validVersion(expectedVersion));
    }
    public boolean menuNameExists(long tenantId, String name, Long id) {
        return menus.menuNameExists(tenantId, requiredText(name, "Menu name"), id);
    }
    public boolean menuPathExists(long tenantId, String path, Long id) {
        return menus.menuPathExists(tenantId, requiredText(path, "Menu path"), id);
    }

    private static void validateUserCreate(IdentityModels.UserCreateCommand command) {
        Objects.requireNonNull(command, "command"); requiredText(command.username(), "Username");
        requiredText(command.name(), "Name"); positiveId(command.departmentId());
        Objects.requireNonNull(command.roleIds(), "roleIds is required");
        validCollectionSize(command.roleIds(), MAX_ROLES_PER_MEMBERSHIP, "roleIds");
        command.roleIds().forEach(IdentityAdministrationService::positiveId); validStatus(command.status());
    }
    private static void validateMembershipUpdate(IdentityModels.MembershipUpdateCommand command) {
        Objects.requireNonNull(command, "command"); positiveId(command.departmentId());
        Objects.requireNonNull(command.roleIds(), "roleIds is required");
        validCollectionSize(command.roleIds(), MAX_ROLES_PER_MEMBERSHIP, "roleIds");
        command.roleIds().forEach(IdentityAdministrationService::positiveId); validStatus(command.status());
        if (command.userVersion() < 0) throw new InvalidCommandException("userVersion is invalid");
        boolean identityUpdate = command.username() != null || command.name() != null
            || command.identityVersion() != null || command.credentialVersion() != null
            || command.remark() != null;
        if (identityUpdate) {
            requiredText(command.username(), "Username");
            requiredText(command.name(), "Name");
            if (command.identityVersion() == null || command.identityVersion() < 0) {
                throw new InvalidCommandException("identityVersion is invalid");
            }
            if (command.credentialVersion() == null || command.credentialVersion() < 0) {
                throw new InvalidCommandException("credentialVersion is invalid");
            }
        }
    }
    private static void validateRole(IdentityModels.RoleCommand command) {
        Objects.requireNonNull(command, "command"); requiredText(command.name(), "Role name");
        Objects.requireNonNull(command.menuIds(), "menuIds is required");
        validCollectionSize(command.menuIds(), MAX_MENUS_PER_ROLE, "menuIds");
        command.menuIds().forEach(IdentityAdministrationService::positiveId); validStatus(command.status());
    }
    private static void validateDepartment(IdentityModels.DepartmentCommand command) {
        Objects.requireNonNull(command, "command"); requiredText(command.name(), "Department name");
        if (command.parentId() != null) positiveId(command.parentId()); validStatus(command.status());
    }
    private static void validateMenu(IdentityModels.MenuCommand command) {
        Objects.requireNonNull(command, "command"); requiredText(command.name(), "Menu name");
        requiredText(command.type(), "Menu type"); if (command.parentId() != null) positiveId(command.parentId());
        validStatus(command.status());
    }
    private static int validPage(int page) { return Math.max(page, 1); }
    private static void validOffset(int page, int pageSize) {
        long offset = (long) (page - 1) * pageSize;
        if (offset > Integer.MAX_VALUE) {
            throw new InvalidCommandException("Page offset exceeds the supported range");
        }
    }
    private static AdministrationActor validActor(AdministrationActor actor) {
        return Objects.requireNonNull(actor, "actor");
    }
    private static void validRange(java.time.Instant start, java.time.Instant end) {
        if (start != null && end != null && start.isAfter(end)) throw new InvalidCommandException("Invalid time range");
    }
    private static int validPageSize(int size) {
        if (size < 1 || size > 200) throw new InvalidCommandException("pageSize must be between 1 and 200"); return size;
    }
    private static void validCollectionSize(List<?> values, int maximum, String label) {
        if (values.size() > maximum) {
            throw new InvalidCommandException(label + " must contain at most " + maximum + " entries");
        }
    }
    private static <T> List<T> boundedTree(List<T> rows, Function<T, Long> id,
                                           Function<T, Long> parentId) {
        Objects.requireNonNull(rows, "Tree rows are required");
        if (rows.size() > MAX_TREE_NODES) {
            throw new TreeLimitExceededException("Tree node limit exceeded");
        }
        Map<Long, T> byId = new HashMap<>(rows.size());
        for (T row : rows) {
            Long nodeId = Objects.requireNonNull(id.apply(row), "Tree node identifier is required");
            if (byId.put(nodeId, row) != null) {
                throw new TreeLimitExceededException("Tree contains duplicate identifiers");
            }
        }
        for (T row : rows) {
            T current = row;
            int depth = 0;
            Set<Long> path = new HashSet<>();
            while (current != null) {
                Long nodeId = id.apply(current);
                if (!path.add(nodeId)) throw new TreeLimitExceededException("Tree contains a cycle");
                if (++depth > MAX_TREE_DEPTH) {
                    throw new TreeLimitExceededException("Tree depth limit exceeded");
                }
                Long parent = parentId.apply(current);
                current = parent == null ? null : byId.get(parent);
            }
        }
        return rows;
    }
    private static long positiveId(long id) {
        if (id <= 0) throw new InvalidCommandException("Identifier must be positive"); return id;
    }
    private static long validVersion(long version) {
        if (version < 0) throw new InvalidCommandException("expectedVersion is invalid");
        return version;
    }
    private static void validStatus(int status) {
        if (status != 0 && status != 1) throw new InvalidCommandException("Status must be 0 or 1");
    }
    private static String requiredText(String value, String label) {
        if (value == null || value.isBlank()) throw new InvalidCommandException(label + " is required"); return value.trim();
    }

    public static final class InvalidCommandException extends RuntimeException {
        public InvalidCommandException(String message) { super(message); }
    }
    public static final class ResourceNotFoundException extends RuntimeException {
        public ResourceNotFoundException(String message) { super(message); }
    }
    public static final class OptimisticLockException extends RuntimeException {
        public OptimisticLockException() { super("The record has changed; reload and retry"); }
    }
    public static final class DataConflictException extends RuntimeException {
        public DataConflictException(String message) { super(message); }
    }
    public static final class TreeLimitExceededException extends RuntimeException {
        public TreeLimitExceededException(String message) { super(message); }
    }
}
